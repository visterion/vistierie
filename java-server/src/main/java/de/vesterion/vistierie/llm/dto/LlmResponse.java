package de.vesterion.vistierie.llm.dto;

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
        /** Raw content blocks from the provider, carrying tool_use payloads. Null when the
         *  provider returned none — callers that send no tools ignore this field. */
        JsonNode content_blocks
) {}
