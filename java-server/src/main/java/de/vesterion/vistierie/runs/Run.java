package de.vesterion.vistierie.runs;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record Run(
        String id,
        UUID tenantId,
        UUID agentId,
        JsonNode agentSnapshot,
        int agentVersion,
        String parentRunId,
        String trigger,
        String status,
        JsonNode payload,
        JsonNode messagesSnapshot,
        JsonNode output,
        String summary,
        String error,
        String completionWebhook,
        String completionWebhookToken,
        Instant startedAt,
        Instant finishedAt
) {}
