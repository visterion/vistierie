package de.vesterion.vistierie.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vesterion.vistierie.pricing.Usage;
import tools.jackson.databind.JsonNode;

public record LlmResponse(
        String text,
        String stop_reason,
        Usage usage,
        String provider,
        String model,
        long cost_micros,
        String llm_call_id,
        /** Raw content blocks from the provider, carrying tool_use payloads. Absent from the
         *  serialized response when the request sent no {@code tools} — toolless callers see no
         *  {@code content_blocks} key at all, keeping their response bit-for-bit unchanged. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        JsonNode content_blocks
) {}
