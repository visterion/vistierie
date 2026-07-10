package de.vesterion.vistierie.routing;

import java.time.Instant;
import java.util.UUID;

public record RoutingRule(
        UUID id,
        UUID tenantId,
        String realm,
        String purpose,
        String provider,
        String model,
        String fallbackProvider,
        String fallbackModel,
        String effort,
        int priority,
        boolean allowOverride,
        boolean locked,
        Instant createdAt,
        Instant updatedAt
) {
    /** Compatibility constructor: rule without effort. */
    public RoutingRule(UUID id, UUID tenantId, String realm, String purpose,
                       String provider, String model,
                       String fallbackProvider, String fallbackModel,
                       int priority, boolean allowOverride, boolean locked,
                       Instant createdAt, Instant updatedAt) {
        this(id, tenantId, realm, purpose, provider, model,
                fallbackProvider, fallbackModel, null,
                priority, allowOverride, locked, createdAt, updatedAt);
    }

    /** Compatibility constructor: rule without fallback. */
    public RoutingRule(UUID id, UUID tenantId, String realm, String purpose,
                       String provider, String model, int priority,
                       boolean allowOverride, boolean locked,
                       Instant createdAt, Instant updatedAt) {
        this(id, tenantId, realm, purpose, provider, model, null, null,
                priority, allowOverride, locked, createdAt, updatedAt);
    }

    /** 3 = realm+purpose, 2 = realm only, 1 = purpose only, 0 = wildcard. */
    public int specificity() {
        int s = 0;
        if (realm != null) s += 2;
        if (purpose != null) s += 1;
        return s;
    }

    public boolean matches(String callRealm, String callPurpose) {
        if (realm != null && !realm.equals(callRealm)) return false;
        if (purpose != null && !purpose.equals(callPurpose)) return false;
        return true;
    }

    public boolean effectiveAllowOverride() {
        return allowOverride && !locked;
    }
}
