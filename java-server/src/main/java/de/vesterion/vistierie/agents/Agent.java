package de.vesterion.vistierie.agents;

import tools.jackson.databind.JsonNode;

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
        String webhookToken,
        boolean paused,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String schedule,
        Instant lastTickAt
) {}
