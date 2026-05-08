package de.vesterion.vistierie.agents.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record PatchAgentRequest(
        Boolean paused,
        String system_prompt,
        String model_purpose,
        List<ToolDef> tools,
        JsonNode output_schema,
        Integer max_turns,
        Integer max_run_seconds,
        String webhook_token
) {}
