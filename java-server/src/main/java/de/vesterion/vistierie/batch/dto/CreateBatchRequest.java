package de.vesterion.vistierie.batch.dto;

import de.vesterion.vistierie.batch.BatchItemRequest;

import java.util.List;

public record CreateBatchRequest(
        List<BatchItemRequest> items,
        String completion_webhook,
        String completion_webhook_token
) {}
