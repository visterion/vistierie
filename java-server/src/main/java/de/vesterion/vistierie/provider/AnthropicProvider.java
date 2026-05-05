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

    // package-private ctor for tests
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

            var text = resp.path("content").get(0).path("text").asText();
            var stopReason = resp.path("stop_reason").asText();
            var u = resp.path("usage");
            var usage = new Usage(
                    u.path("input_tokens").asInt(),
                    u.path("output_tokens").asInt(),
                    u.path("cache_creation_input_tokens").asInt(0),
                    u.path("cache_read_input_tokens").asInt(0)
            );
            return new ProviderResponse(text, stopReason, usage, resp.path("model").asText());
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
