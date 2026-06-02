package de.vesterion.vistierie.streaming;

import java.time.Instant;
import java.util.UUID;

public record StreamingSession(
        UUID id,
        UUID tenantId,
        UUID agentId,
        Instant openedAt,
        Instant closesAt,
        Instant lastPollAt,
        String status,
        Instant createdAt
) {}
