package de.vesterion.vistierie.routing.admin.dto;

public record PatchRoutingRuleRequest(
        String provider,
        String model,
        Integer priority,
        Boolean allow_override,
        Boolean locked
) {}
