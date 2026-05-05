package de.vesterion.vistierie.llm.dto;

import de.vesterion.vistierie.pricing.Usage;

public record LlmResponse(
        String text,
        String stop_reason,
        Usage usage,
        String provider,
        String model,
        long cost_micros,
        String llm_call_id
) {}
