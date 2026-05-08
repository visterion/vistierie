package de.vesterion.vistierie.provider;

public record BatchStatus(
        String anthropicBatchId,
        String processingStatus,                // "in_progress" | "ended" | "canceling"
        int processing, int succeeded, int errored, int canceled, int expired,
        String resultsUrl                       // null until processingStatus == "ended"
) {}
