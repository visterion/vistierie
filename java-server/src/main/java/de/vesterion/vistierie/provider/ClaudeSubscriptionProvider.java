package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider backed by the claude-bridge sidecar, which calls the Claude Agent SDK
 * authenticated with a Claude Max subscription token. Batch is not supported
 * (interface defaults throw) — batch traffic stays on the API-key provider.
 */
public class ClaudeSubscriptionProvider implements LlmProvider {

    public static final String NAME = "claude-subscription";

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeSubscriptionProvider(RestClient http) { this.http = http; }

    @Override public String name() { return NAME; }

    @Override public ProviderResponse complete(ProviderRequest req) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", req.model());
        body.put("max_tokens", req.maxTokens());
        if (req.system() != null && !req.system().isEmpty()) body.put("system", req.system());
        body.put("messages", req.messages());
        return call(body);
    }

    @Override public ProviderResponse vision(String model, int maxTokens,
                                             String mediaType, String base64, String prompt) {
        var content = List.<Map<String, Object>>of(
                Map.of("type", "image", "source", Map.of(
                        "type", "base64", "media_type", mediaType, "data", base64)),
                Map.of("type", "text", "text", prompt));
        return callWithContent(model, maxTokens, content);
    }

    @Override public ProviderResponse visionMulti(String model, int maxTokens,
                                                  List<ImageInput> images, String prompt) {
        var content = new ArrayList<Map<String, Object>>();
        for (ImageInput img : images) {
            content.add(Map.of("type", "image", "source", Map.of(
                    "type", "base64", "media_type", img.mediaType(), "data", img.base64())));
        }
        content.add(Map.of("type", "text", "text", prompt));
        return callWithContent(model, maxTokens, content);
    }

    private ProviderResponse callWithContent(String model, int maxTokens,
                                             List<Map<String, Object>> content) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return call(body);
    }

    private ProviderResponse call(Map<String, Object> body) {
        try {
            JsonNode resp = http.post()
                    .uri("/v1/complete")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(s -> true, (rq, rs) -> {
                        if (rs.getStatusCode().isError()) {
                            var raw = new String(rs.getBody().readAllBytes());
                            String code = "bridge_error";
                            try {
                                var n = mapper.readTree(raw).path("error");
                                if (n.has("code")) code = n.get("code").asText();
                            } catch (Exception ignore) { }
                            int status = rs.getStatusCode().value();
                            // 429 = subscription quota (fallback trigger, rate_limited);
                            // everything else = bridge/SDK failure → 502 (fallback trigger, error)
                            if (status == 429) {
                                throw new ProviderException(429, "subscription_exhausted", raw);
                            }
                            throw new ProviderException(502, code, raw);
                        }
                    })
                    .body(JsonNode.class);
            return parse(resp);
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

    private ProviderResponse parse(JsonNode resp) {
        var u = resp.path("usage");
        var usage = new Usage(
                Math.max(0, u.path("input_tokens").asInt(0)),
                Math.max(0, u.path("output_tokens").asInt(0)),
                Math.max(0, u.path("cache_creation_input_tokens").asInt(0)),
                Math.max(0, u.path("cache_read_input_tokens").asInt(0)));
        return new ProviderResponse(
                resp.path("text").asText(""),
                resp.path("stop_reason").asText("end_turn"),
                usage,
                resp.path("model").asText(""));
    }
}
