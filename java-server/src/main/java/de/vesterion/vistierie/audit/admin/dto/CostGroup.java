package de.vesterion.vistierie.audit.admin.dto;

import java.util.Map;

public record CostGroup(
        Map<String, String> dimensions,
        long calls,
        long input_tokens,
        long output_tokens,
        long cache_creation_input_tokens,
        long cache_read_input_tokens,
        long cost_micros,
        double cost_eur
) {}
