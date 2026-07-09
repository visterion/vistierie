package de.vesterion.vistierie.agents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

public record Agent(
        UUID id,
        UUID tenantId,
        String name,
        String systemPrompt,
        String modelPurpose,
        JsonNode tools,
        JsonNode outputSchema,
        int maxTurns,
        int maxRunSeconds,
        Integer maxTokens,
        String webhookToken,
        boolean paused,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String schedule,
        Instant lastTickAt,
        String completionWebhook,
        String completionWebhookToken,
        String eventSourceUrl,
        Integer sessionDurationSeconds,
        Integer pollIntervalSeconds,
        JsonNode mcpCredentials
) {
    /** Convenience constructor leaving {@code maxTokens} unset (runtime default applies) and
     *  defaulting {@code mcpCredentials} to an empty object. */
    public Agent(UUID id, UUID tenantId, String name, String systemPrompt, String modelPurpose,
                 JsonNode tools, JsonNode outputSchema, int maxTurns, int maxRunSeconds,
                 String webhookToken, boolean paused, int version, Instant createdAt, Instant updatedAt,
                 String schedule, Instant lastTickAt, String completionWebhook, String completionWebhookToken,
                 String eventSourceUrl, Integer sessionDurationSeconds, Integer pollIntervalSeconds) {
        this(id, tenantId, name, systemPrompt, modelPurpose, tools, outputSchema, maxTurns, maxRunSeconds,
                null, webhookToken, paused, version, createdAt, updatedAt, schedule, lastTickAt,
                completionWebhook, completionWebhookToken, eventSourceUrl, sessionDurationSeconds, pollIntervalSeconds,
                JsonNodeFactory.instance.objectNode());
    }

    /** Convenience constructor defaulting {@code mcpCredentials} to an empty object. */
    public Agent(UUID id, UUID tenantId, String name, String systemPrompt, String modelPurpose,
                 JsonNode tools, JsonNode outputSchema, int maxTurns, int maxRunSeconds, Integer maxTokens,
                 String webhookToken, boolean paused, int version, Instant createdAt, Instant updatedAt,
                 String schedule, Instant lastTickAt, String completionWebhook, String completionWebhookToken,
                 String eventSourceUrl, Integer sessionDurationSeconds, Integer pollIntervalSeconds) {
        this(id, tenantId, name, systemPrompt, modelPurpose, tools, outputSchema, maxTurns, maxRunSeconds,
                maxTokens, webhookToken, paused, version, createdAt, updatedAt, schedule, lastTickAt,
                completionWebhook, completionWebhookToken, eventSourceUrl, sessionDurationSeconds, pollIntervalSeconds,
                JsonNodeFactory.instance.objectNode());
    }
}
