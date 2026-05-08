package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        value = "vistierie.mock-llm", havingValue = "false", matchIfMissing = true)
@org.springframework.context.annotation.Profile("!test-stub-llm")

@Component
public class AnthropicProvider implements LlmProvider {

    private final RestClient http;
    private final String apiKey;
    private final int timeoutSeconds;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public AnthropicProvider(
            @Value("${vistierie.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${vistierie.anthropic.api-key}") String apiKey,
            @Value("${vistierie.anthropic.timeout-seconds:60}") int timeoutSeconds) {
        this(RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory()).build(), apiKey, timeoutSeconds);
    }

    // package-private ctor for tests (inject a pre-built RestClient)
    AnthropicProvider(RestClient http, String apiKey, int timeoutSeconds) {
        this.http = http;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
    }


    @Override public String name() { return "anthropic"; }

    @Override public ProviderResponse complete(ProviderRequest req) {
        var body = new HashMap<String, Object>();
        body.put("model", req.model());
        body.put("max_tokens", req.maxTokens());
        if (req.temperature() != null) body.put("temperature", req.temperature());
        if (req.system() != null) body.put("system", req.system());
        body.put("messages", req.messages());
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.put("tools", req.tools());
        }
        return call(body);
    }

    @Override public ProviderResponse vision(String model, int maxTokens,
                                              String mediaType, String base64, String prompt) {
        var body = Map.<String, Object>of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", mediaType,
                                                "data", base64)),
                                Map.of("type", "text", "text", prompt))))
        );
        return call(body);
    }

    @Override
    public BatchSubmission submitBatch(java.util.List<BatchItem> items) {
        var requests = items.stream().map(item -> {
            var params = new java.util.LinkedHashMap<String, Object>();
            params.put("model", item.request().model());
            params.put("max_tokens", item.request().maxTokens());
            if (item.request().temperature() != null) params.put("temperature", item.request().temperature());
            if (item.request().system() != null) params.put("system", item.request().system());
            params.put("messages", item.request().messages());
            if (item.request().tools() != null && !item.request().tools().isEmpty()) {
                params.put("tools", item.request().tools());
            }
            return java.util.Map.of("custom_id", item.customId(), "params", params);
        }).toList();

        var body = java.util.Map.of("requests", requests);
        JsonNode resp = http.post()
                .uri("/v1/messages/batches")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new BatchSubmission(resp.path("id").asText(), items.size());
    }

    @Override
    public BatchStatus getBatch(String anthropicBatchId) {
        JsonNode resp = http.get()
                .uri("/v1/messages/batches/" + anthropicBatchId)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .retrieve()
                .body(JsonNode.class);
        var counts = resp.path("request_counts");
        String resultsUrl = resp.path("results_url").isNull() || resp.path("results_url").isMissingNode()
                ? null : resp.path("results_url").asText();
        return new BatchStatus(
                resp.path("id").asText(),
                resp.path("processing_status").asText(),
                counts.path("processing").asInt(0),
                counts.path("succeeded").asInt(0),
                counts.path("errored").asInt(0),
                counts.path("canceled").asInt(0),
                counts.path("expired").asInt(0),
                resultsUrl
        );
    }

    @Override
    public java.util.stream.Stream<BatchResult> streamResults(String resultsUrl) {
        var inputStream = http.get()
                .uri(resultsUrl)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .retrieve()
                .body(java.io.InputStream.class);
        if (inputStream == null) return java.util.stream.Stream.empty();
        var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
        return reader.lines()
                .filter(line -> !line.isBlank())
                .map(this::parseBatchResultLine)
                .onClose(() -> {
                    try { reader.close(); } catch (Exception ignored) {}
                });
    }

    private BatchResult parseBatchResultLine(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            String customId = node.path("custom_id").asText();
            JsonNode result = node.path("result");
            String type = result.path("type").asText();
            if ("succeeded".equals(type)) {
                JsonNode msg = result.path("message");
                String model = msg.path("model").asText();
                String stopReason = msg.path("stop_reason").asText();
                JsonNode content = msg.path("content");
                String text = "";
                if (content.isArray() && content.size() > 0
                        && "text".equals(content.get(0).path("type").asText())) {
                    text = content.get(0).path("text").asText();
                }
                JsonNode u = msg.path("usage");
                var usage = new Usage(
                        u.path("input_tokens").asInt(0),
                        u.path("output_tokens").asInt(0),
                        u.path("cache_creation_input_tokens").asInt(0),
                        u.path("cache_read_input_tokens").asInt(0));
                return new BatchResult(customId, type, text, stopReason, usage, model, content, null);
            }
            if ("errored".equals(type)) {
                String emsg = result.path("error").path("message").asText();
                return new BatchResult(customId, type, null, null, null, null, null, emsg);
            }
            // canceled / expired — return mostly-null result
            return new BatchResult(customId, type, null, null, null, null, null, null);
        } catch (Exception e) {
            throw new RuntimeException("malformed batch result line: " + line, e);
        }
    }

    private ProviderResponse call(Map<String, Object> body) {
        try {
            var resp = http.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(s -> true, (req, res) -> {
                        if (res.getStatusCode().isError()) {
                            var err = new String(res.getBody().readAllBytes());
                            String code = "unknown";
                            try {
                                var n = mapper.readTree(err);
                                if (n.has("error") && n.get("error").has("type")) {
                                    code = n.get("error").get("type").asText();
                                }
                            } catch (Exception ignore) {}
                            throw new ProviderException(res.getStatusCode().value(), code, err);
                        }
                    })
                    .body(JsonNode.class);

            StringBuilder sb = new StringBuilder();
            for (JsonNode block : resp.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            var text = sb.toString();
            var stopReason = resp.path("stop_reason").asText();
            var u = resp.path("usage");
            var usage = new Usage(
                    u.path("input_tokens").asInt(),
                    u.path("output_tokens").asInt(),
                    u.path("cache_creation_input_tokens").asInt(0),
                    u.path("cache_read_input_tokens").asInt(0)
            );
            return new ProviderResponse(text, stopReason, usage, resp.path("model").asText(),
                    resp.path("content"));
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            // Spring may wrap our ProviderException in a RestClientException or similar
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof ProviderException pe) {
                    throw pe;
                }
                cause = cause.getCause();
            }
            throw new ProviderException(502, "transport_error", e.getMessage());
        }
    }
}
