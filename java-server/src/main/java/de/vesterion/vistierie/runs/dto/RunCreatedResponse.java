package de.vesterion.vistierie.runs.dto;

public record RunCreatedResponse(
        String run_id,
        String agent_name,
        int agent_version,
        String status
) {}
