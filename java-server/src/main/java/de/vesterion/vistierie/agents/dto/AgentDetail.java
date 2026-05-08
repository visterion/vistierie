package de.vesterion.vistierie.agents.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record AgentDetail(
        String id, String name,
        String system_prompt, String model_purpose,
        List<ToolDef> tools, JsonNode output_schema,
        int max_turns, int max_run_seconds,
        boolean paused, int version,
        Instant created_at, Instant updated_at
) {}
