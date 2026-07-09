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
        Integer max_tokens,
        @NotBlank String webhook_token,
        String schedule,
        String completion_webhook,
        String completion_webhook_token,
        String event_source_url,
        Integer session_duration_seconds,
        Integer poll_interval_seconds,
        JsonNode mcp_credentials
) {
    /** Convenience constructor without {@code max_tokens} or {@code mcp_credentials}. */
    public CreateAgentRequest(String name, String system_prompt, String model_purpose,
            List<ToolDef> tools, JsonNode output_schema, Integer max_turns, Integer max_run_seconds,
            String webhook_token, String schedule, String completion_webhook, String completion_webhook_token,
            String event_source_url, Integer session_duration_seconds, Integer poll_interval_seconds) {
        this(name, system_prompt, model_purpose, tools, output_schema, max_turns, max_run_seconds,
                null, webhook_token, schedule, completion_webhook, completion_webhook_token,
                event_source_url, session_duration_seconds, poll_interval_seconds, null);
    }
}
