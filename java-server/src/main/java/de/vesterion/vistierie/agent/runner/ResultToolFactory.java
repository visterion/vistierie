package de.vesterion.vistierie.agent.runner;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Builds the {@code submit_result} tool definition from an agent's {@code output_schema}.
 *
 * <p>This tool is an OUTPUT CHANNEL, not an executable tool: {@link AgentRunner} intercepts a
 * call to it and ends the run with the call's {@code input}. It is never dispatched to
 * {@code ToolDispatcher}. Delivering the payload as {@code tool_use.input} means the SDK/API
 * layer serializes it, so the model cannot emit an unescaped quote inside a string value —
 * the failure mode that killed two gropar runs (2026-08-10, 2026-08-11).
 */
@Component
public class ResultToolFactory {

    public static final String TOOL_NAME = "submit_result";

    private static final String DESCRIPTION =
            "Deliver the final structured result. Call this exactly once when your analysis "
            + "is complete. Pass the result as this tool's input — do not write it as text.";

    public Map<String, Object> build(JsonNode outputSchema) {
        if (outputSchema == null || outputSchema.isNull()) {
            throw new IllegalArgumentException("outputSchema must not be null");
        }
        return Map.of(
                "name", TOOL_NAME,
                "description", DESCRIPTION,
                "input_schema", outputSchema);
    }
}
