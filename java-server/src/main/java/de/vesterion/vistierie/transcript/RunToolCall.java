package de.vesterion.vistierie.transcript;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record RunToolCall(
        String id,
        String runId,
        UUID tenantId,
        String llmCallId,
        int turnIndex,
        String toolUseId,
        String toolName,
        String toolType,
        JsonNode input,
        JsonNode output,
        boolean isError,
        String errorDetail,
        Instant createdAt
) {}
