package de.vesterion.vistierie.batch.dto;

public record BatchCreatedResponse(
        String run_id,
        String agent_name,
        int agent_version,
        String status,
        int items_total,
        String anthropic_batch_id
) {}
