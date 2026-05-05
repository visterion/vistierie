package de.vesterion.vistierie.tenants;

import java.time.Instant;
import java.util.UUID;

public record Tenant(
        UUID id,
        String name,
        String tokenHash,
        Instant killUntil,
        String killReason,
        String killSetBy,
        Instant createdAt
) {}
