package de.vesterion.vistierie.audit.admin.dto;

import java.time.Instant;

public record AdminLlmCallRow(
        String id, String tenant, String run_id, String purpose, String realm,
        String provider, String model, String endpoint,
        int input_tokens, int output_tokens,
        int cache_creation_input_tokens, int cache_read_input_tokens,
        long cost_micros, int duration_ms, String status,
        String error_code, Instant created_at
) {}
