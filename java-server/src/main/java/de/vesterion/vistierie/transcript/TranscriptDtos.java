package de.vesterion.vistierie.transcript;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class TranscriptDtos {

    public record Tokens(int input, int output, int cacheCreate, int cacheRead) {}

    public record ToolUseDto(String tool_use_id, String name, JsonNode input) {}

    public record ToolCallDto(String tool_use_id, String name, String type,
                              JsonNode input, JsonNode output,
                              boolean is_error, String error_detail) {}

    /** llm_input_messages is null in compact/digest views. */
    public record TurnDto(int index,
                          JsonNode llm_input_messages,
                          String text,
                          String stop_reason,
                          List<ToolUseDto> tool_use,
                          Tokens tokens,
                          List<ToolCallDto> tool_calls,
                          JsonNode raw_response_content) {}

    public record TranscriptDto(String run_id, String agent, String status, String model,
                                int turn_count, Instant started_at, Instant finished_at,
                                List<TurnDto> turns, JsonNode final_output, String error) {}

    public record ToolUsageDto(String name, int count, int error_count) {}

    public record DigestDto(String run_id, String agent, String status, String model,
                            int turn_count, long token_total,
                            List<ToolUsageDto> tools, JsonNode final_output, String error) {}

    private TranscriptDtos() {}
}
