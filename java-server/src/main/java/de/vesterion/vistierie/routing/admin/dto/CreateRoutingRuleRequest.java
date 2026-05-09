package de.vesterion.vistierie.routing.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRoutingRuleRequest(
        @NotBlank String tenant,
        String realm,
        String purpose,
        @NotBlank String provider,
        @NotBlank String model,
        Integer priority,
        boolean allow_override,
        boolean locked
) {}
