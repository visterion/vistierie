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
        int priority,
        boolean allowOverride,
        boolean locked,
        Instant createdAt,
        Instant updatedAt
) {
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
