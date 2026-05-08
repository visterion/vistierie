package de.vesterion.vistierie.runs.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

public record RunDetail(
        String run_id,
        String agent_name,
        int agent_version,
        String trigger,
        String status,
        Instant started_at,
        Instant finished_at,
        String summary,
        JsonNode output,
        String error,
        String parent_run_id,
        Map<String, Integer> children_summary
) {}
