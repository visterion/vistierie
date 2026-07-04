package de.vesterion.vistierie.routing.admin.dto;

public record PatchRoutingRuleRequest(
        String provider,
        String model,
        String fallback_provider,
        String fallback_model,
        Boolean clear_fallback,
        Integer priority,
        Boolean allow_override,
        Boolean locked
) {}
