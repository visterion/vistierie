package de.vesterion.vistierie.agents.dto;

import java.time.Instant;
import java.util.UUID;

public record StreamingSessionDto(
        UUID id,
        Instant opened_at,
        Instant closes_at,
        Instant last_poll_at,
        String status
) {}
