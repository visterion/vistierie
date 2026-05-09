package de.vesterion.vistierie.routing.admin.dto;

import de.vesterion.vistierie.routing.RoutingRule;

import java.time.Instant;
import java.util.UUID;

public record RoutingRuleResponse(
        UUID id, String tenant_id, String realm, String purpose,
        String provider, String model, int priority,
        boolean allow_override, boolean locked,
        Instant created_at, Instant updated_at
) {
    public static RoutingRuleResponse of(RoutingRule r) {
        return new RoutingRuleResponse(
                r.id(), r.tenantId().toString(), r.realm(), r.purpose(),
                r.provider(), r.model(), r.priority(),
                r.allowOverride(), r.locked(),
                r.createdAt(), r.updatedAt());
    }
}
