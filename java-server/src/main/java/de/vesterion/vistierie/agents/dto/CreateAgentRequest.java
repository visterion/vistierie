package de.vesterion.vistierie.agents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record CreateAgentRequest(
        @NotBlank String name,
        @NotBlank String system_prompt,
        @NotBlank String model_purpose,
        @NotNull List<ToolDef> tools,
        JsonNode output_schema,
        Integer max_turns,
        Integer max_run_seconds,
        @NotBlank String webhook_token
) {}
