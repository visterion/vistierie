package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider for any LLM API that speaks the OpenAI /v1/chat/completions wire format.
 * One bean is registered per entry under vistierie.providers.* in YAML, each with
 * its own name, base-url, and api-key. Same class serves OpenAI, xAI, and any other
 * OpenAI-compatible endpoint without code changes.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private final String name;
    private final RestClient http;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleProvider(String name, RestClient http, String apiKey) {
        this.name = name;
        this.http = http;
        this.apiKey = apiKey;
    }

    @Override public String name() { return name; }

    @Override public ProviderResponse complete(ProviderRequest req) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", req.model());
        body.put("max_completion_tokens", req.maxTokens());
        if (req.temperature() != null) body.put("temperature", req.temperature());
        body.put("messages", buildMessages(req.system(), req.messages()));
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.put("tools", translateTools(req.tools()));
        }
        return call(body);
    }

    @Override public ProviderResponse vision(String model, int maxTokens,
                                              String mediaType, String base64, String prompt) {
        var dataUrl = "data:" + mediaType + ";base64," + base64;
        var content = List.<Map<String, Object>>of(
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                Map.of("type", "text", "text", prompt));
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("max_completion_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return call(body);
    }

    @Override public ProviderResponse visionMulti(String model, int maxTokens,
                                                  java.util.List<ImageInput> images, String prompt) {
        var content = new ArrayList<Map<String, Object>>();
        for (ImageInput img : images) {
            content.add(Map.of("type", "image_url",
                    "image_url", Map.of("url", "data:" + img.mediaType() + ";base64," + img.base64())));
        }
        content.add(Map.of("type", "text", "text", prompt));
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("max_completion_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return call(body);
    }

    private List<Map<String, Object>> buildMessages(String system,
                                                    List<Map<String, Object>> userMessages) {
        var out = new ArrayList<Map<String, Object>>();
        if (system != null && !system.isEmpty()) {
            out.add(Map.of("role", "system", "content", system));
        }
        out.addAll(userMessages);
        return out;
    }

    /**
     * Translate Anthropic-shape tool definitions
     *   {name, description, input_schema}
     * to OpenAI-shape:
     *   {type: "function", function: {name, description, parameters}}
     */
    private List<Map<String, Object>> translateTools(List<Map<String, Object>> tools) {
        var out = new ArrayList<Map<String, Object>>(tools.size());
        for (var t : tools) {
            var fn = new LinkedHashMap<String, Object>();
            fn.put("name", t.get("name"));
            if (t.containsKey("description")) fn.put("description", t.get("description"));
            if (t.containsKey("input_schema")) fn.put("parameters", t.get("input_schema"));
            else fn.put("parameters", Map.of("type", "object"));
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private ProviderResponse call(Map<String, Object> body) {
        try {
            JsonNode resp = http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(s -> true, (req, res) -> {
                        if (res.getStatusCode().isError()) {
                            var raw = new String(res.getBody().readAllBytes());
                            String code = "unknown";
                            try {
                                var n = mapper.readTree(raw).path("error");
                                if (n.has("code") && !n.get("code").isNull()) {
                                    code = n.get("code").asText();
                                } else if (n.has("type")) {
                                    code = n.get("type").asText();
                                }
                            } catch (Exception ignore) { }
                            throw new ProviderException(res.getStatusCode().value(), code, raw);
                        }
                    })
                    .body(JsonNode.class);

            return parseResponse(resp);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof ProviderException pe) throw pe;
                cause = cause.getCause();
            }
            throw new ProviderException(502, "transport_error", e.getMessage());
        }
    }

    private ProviderResponse parseResponse(JsonNode resp) {
        JsonNode choice = resp.path("choices").path(0);
        JsonNode msg = choice.path("message");
        String text = msg.path("content").isTextual() ? msg.path("content").asText() : "";
        String finishReason = choice.path("finish_reason").asText("");
        String stopReason = translateFinishReason(finishReason);

        ArrayNode contentBlocks = synthesizeContentBlocks(text, msg.path("tool_calls"));

        JsonNode u = resp.path("usage");
        int input = Math.max(0, u.path("prompt_tokens").asInt(0));
        int output = Math.max(0, u.path("completion_tokens").asInt(0));
        int cacheRead = Math.max(0, u.path("prompt_tokens_details").path("cached_tokens").asInt(0));
        // OpenAI/xAI bill cached input as discount on input — we record it separately so
        // PriceTable can apply the cache_read rate. Subtract from input to avoid double-count.
        if (cacheRead > input) cacheRead = input;
        input -= cacheRead;
        var usage = new Usage(input, output, 0, cacheRead);

        return new ProviderResponse(text, stopReason, usage,
                resp.path("model").asText(""), contentBlocks);
    }

    private String translateFinishReason(String openaiReason) {
        return switch (openaiReason) {
            case "stop" -> "end_turn";
            case "tool_calls", "function_call" -> "tool_use";
            case "length" -> "max_tokens";
            default -> openaiReason;
        };
    }

    /**
     * Synthesize Anthropic-shaped content blocks from OpenAI message so the existing
     * ToolUseParser works without a provider-specific branch.
     */
    private ArrayNode synthesizeContentBlocks(String text, JsonNode toolCalls) {
        ArrayNode arr = mapper.createArrayNode();
        if (text != null && !text.isEmpty()) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            arr.add(textBlock);
        }
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                ObjectNode toolBlock = mapper.createObjectNode();
                toolBlock.put("type", "tool_use");
                toolBlock.put("id", tc.path("id").asText(""));
                toolBlock.put("name", tc.path("function").path("name").asText(""));
                String argsStr = tc.path("function").path("arguments").asText("{}");
                try {
                    toolBlock.set("input", mapper.readTree(argsStr));
                } catch (Exception e) {
                    toolBlock.set("input", mapper.createObjectNode().put("_raw", argsStr));
                }
                arr.add(toolBlock);
            }
        }
        return arr;
    }
}
