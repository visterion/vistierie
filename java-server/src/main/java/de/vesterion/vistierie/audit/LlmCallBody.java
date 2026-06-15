package de.vesterion.vistierie.audit;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

public record LlmCallBody(
        String callId,
        JsonNode requestJson,
        String responseText,
        JsonNode responseContentJson,
        Instant createdAt
) {}
