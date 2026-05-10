package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class BedrockProvider implements LlmProvider {

    private final BedrockRuntimeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    BedrockProvider(BedrockRuntimeClient client) {
        this.client = client;
    }

    @Override
    public String name() { return "bedrock"; }

    @Override
    public ProviderResponse complete(ProviderRequest req) {
        var builder = ConverseRequest.builder()
                .modelId(req.model())
                .messages(toBedrockMessages(req.messages()))
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(req.maxTokens())
                        .temperature(req.temperature() != null
                                ? req.temperature().floatValue() : null)
                        .build());

        if (req.system() != null && !req.system().isBlank()) {
            builder.system(SystemContentBlock.builder().text(req.system()).build());
        }

        if (req.tools() != null && !req.tools().isEmpty()) {
            builder.toolConfig(buildToolConfig(req.tools())); // req.toolChoice() not yet forwarded
        }

        return call(builder.build(), req.model());
    }

    @Override
    public ProviderResponse vision(String model, int maxTokens,
                                    String mediaType, String base64, String prompt) {
        String format = mediaType.replace("image/", "");
        var imageBlock = ImageBlock.builder()
                .format(ImageFormat.fromValue(format))
                .source(ImageSource.builder()
                        .bytes(SdkBytes.fromByteArray(
                                java.util.Base64.getDecoder().decode(base64)))
                        .build())
                .build();

        var request = ConverseRequest.builder()
                .modelId(model)
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(
                                ContentBlock.fromImage(imageBlock),
                                ContentBlock.fromText(prompt))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(maxTokens)
                        .build())
                .build();

        return call(request, model);
    }

    private ProviderResponse call(ConverseRequest request, String modelId) {
        try {
            return parseResponse(client.converse(request), modelId);
        } catch (ThrottlingException e) {
            throw new LlmProvider.ProviderException(429, "rate_limit_exceeded", e.getMessage());
        } catch (ModelNotReadyException | ModelErrorException e) {
            throw new LlmProvider.ProviderException(503, "model_unavailable", e.getMessage());
        } catch (ValidationException e) {
            throw new LlmProvider.ProviderException(400, "invalid_request", e.getMessage());
        } catch (BedrockRuntimeException e) {
            throw new LlmProvider.ProviderException(502, "transport_error", e.getMessage());
        }
    }

    private ProviderResponse parseResponse(ConverseResponse resp, String modelId) {
        Message msg = resp.output().message();
        String text = msg.content().stream()
                .filter(b -> b.type() == ContentBlock.Type.TEXT)
                .map(ContentBlock::text)
                .collect(Collectors.joining());
        String stopReason = resp.stopReason() != null ? resp.stopReason().toString() : "unknown";
        TokenUsage u = resp.usage();
        var usage = new Usage(u.inputTokens(), u.outputTokens(), 0, 0);
        ArrayNode contentBlocks = synthesizeContentBlocks(msg.content());
        return new ProviderResponse(text, stopReason, usage, modelId, contentBlocks);
    }

    private ArrayNode synthesizeContentBlocks(List<ContentBlock> blocks) {
        ArrayNode arr = mapper.createArrayNode();
        for (ContentBlock block : blocks) {
            if (block.type() == ContentBlock.Type.TEXT) {
                ObjectNode n = mapper.createObjectNode();
                n.put("type", "text");
                n.put("text", block.text());
                arr.add(n);
            } else if (block.type() == ContentBlock.Type.TOOL_USE) {
                ToolUseBlock tu = block.toolUse();
                ObjectNode n = mapper.createObjectNode();
                n.put("type", "tool_use");
                n.put("id", tu.toolUseId());
                n.put("name", tu.name());
                n.set("input", documentToJsonNode(tu.input()));
                arr.add(n);
            }
        }
        return arr;
    }

    private List<Message> toBedrockMessages(List<Map<String, Object>> messages) {
        return messages.stream().map(m -> {
            String role = (String) m.get("role");
            return Message.builder()
                    .role("user".equals(role) ? ConversationRole.USER : ConversationRole.ASSISTANT)
                    .content(toContentBlocks(m.get("content")))
                    .build();
        }).toList();
    }

    private List<ContentBlock> toContentBlocks(Object content) {
        if (content instanceof String s) {
            return List.of(ContentBlock.fromText(s));
        }
        if (content instanceof List<?> list) {
            return list.stream().map(item -> {
                @SuppressWarnings("unchecked")
                var block = (Map<String, Object>) item;
                return switch ((String) block.get("type")) {
                    case "text" ->
                        ContentBlock.fromText((String) block.get("text"));
                    case "tool_use" ->
                        ContentBlock.fromToolUse(ToolUseBlock.builder()
                                .toolUseId((String) block.get("id"))
                                .name((String) block.get("name"))
                                .input(toDocument(block.get("input")))
                                .build());
                    case "tool_result" -> {
                        Object rc = block.get("content");
                        String text = rc instanceof String s2 ? s2 : writeJson(rc);
                        yield ContentBlock.fromToolResult(ToolResultBlock.builder()
                                .toolUseId((String) block.get("tool_use_id"))
                                .content(ToolResultContentBlock.fromText(text))
                                .build());
                    }
                    default -> throw new LlmProvider.ProviderException(400, "invalid_request",
                            "unsupported content block type: " + block.get("type"));
                };
            }).toList();
        }
        return List.of();
    }

    private Document toDocument(Object value) {
        if (value == null) return Document.fromNull();
        if (value instanceof String s) return Document.fromString(s);
        if (value instanceof Boolean b) return Document.fromBoolean(b);
        if (value instanceof Number n) return Document.fromNumber(n.toString());
        if (value instanceof List<?> list) {
            return Document.fromList(list.stream().map(this::toDocument).toList());
        }
        if (value instanceof Map<?, ?> map) {
            var docMap = new LinkedHashMap<String, Document>();
            map.forEach((k, v) -> docMap.put(k.toString(), toDocument(v)));
            return Document.fromMap(docMap);
        }
        return Document.fromString(value.toString());
    }

    private JsonNode documentToJsonNode(Document doc) {
        if (doc == null || doc.isNull()) return mapper.nullNode();
        if (doc.isString()) return mapper.getNodeFactory().textNode(doc.asString());
        if (doc.isBoolean()) return mapper.getNodeFactory().booleanNode(doc.asBoolean());
        if (doc.isNumber()) return mapper.getNodeFactory().numberNode(
                new BigDecimal(doc.asNumber().toString()));
        if (doc.isList()) {
            var arr = mapper.getNodeFactory().arrayNode();
            doc.asList().forEach(item -> arr.add(documentToJsonNode(item)));
            return arr;
        }
        if (doc.isMap()) {
            var obj = mapper.getNodeFactory().objectNode();
            doc.asMap().forEach((k, v) -> obj.set(k, documentToJsonNode(v)));
            return obj;
        }
        return mapper.nullNode();
    }

    private ToolConfiguration buildToolConfig(List<Map<String, Object>> tools) {
        var toolList = tools.stream().map(t -> {
            var specBuilder = ToolSpecification.builder()
                    .name((String) t.get("name"));
            if (t.containsKey("description")) {
                specBuilder.description((String) t.get("description"));
            }
            if (t.containsKey("input_schema")) {
                specBuilder.inputSchema(ToolInputSchema.builder()
                        .json(toDocument(t.get("input_schema")))
                        .build());
            }
            return Tool.builder().toolSpec(specBuilder.build()).build();
        }).toList();
        return ToolConfiguration.builder().tools(toolList).build();
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }
}
