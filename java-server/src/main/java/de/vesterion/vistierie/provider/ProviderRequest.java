package de.vesterion.vistierie.provider;

import java.util.List;
import java.util.Map;

public record ProviderRequest(
        String model,
        int maxTokens,
        Double temperature,
        String system,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        Object toolChoice,
        Map<String, Object> metadata,
        String effort
) {
    /** Compatibility constructor: request without effort. */
    public ProviderRequest(String model, int maxTokens, Double temperature, String system,
                           List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                           Object toolChoice, Map<String, Object> metadata) {
        this(model, maxTokens, temperature, system, messages, tools, toolChoice, metadata, null);
    }
}
