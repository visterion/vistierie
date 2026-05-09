package de.vesterion.vistierie.audit.admin.dto;

import java.time.Instant;

public record AdminRunSummary(
        String id, String tenant, String agent, String trigger, String status,
        Instant started_at, Instant finished_at, Long duration_ms,
        int llm_calls_count, long total_cost_micros
) {}
