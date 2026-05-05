package de.vesterion.vistierie.provider;

import java.util.List;
import java.util.Map;

public record ProviderRequest(
        String model,
        int maxTokens,
        Double temperature,
        String system,
        List<Map<String, Object>> messages,
        Object tools,           // unused in slice 1
        Object toolChoice,      // unused
        Map<String, Object> metadata // unused
) {}
