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
        Integer max_tokens,
        String webhook_token,
        String schedule,
        String completion_webhook,
        String completion_webhook_token,
        String event_source_url,
        Integer session_duration_seconds,
        Integer poll_interval_seconds
) {
    /** Convenience constructor without {@code max_tokens} (runtime default applies). */
    public PatchAgentRequest(Boolean paused, String system_prompt, String model_purpose,
            List<ToolDef> tools, JsonNode output_schema, Integer max_turns, Integer max_run_seconds,
            String webhook_token, String schedule, String completion_webhook, String completion_webhook_token,
            String event_source_url, Integer session_duration_seconds, Integer poll_interval_seconds) {
        this(paused, system_prompt, model_purpose, tools, output_schema, max_turns, max_run_seconds,
                null, webhook_token, schedule, completion_webhook, completion_webhook_token,
                event_source_url, session_duration_seconds, poll_interval_seconds);
    }
}
