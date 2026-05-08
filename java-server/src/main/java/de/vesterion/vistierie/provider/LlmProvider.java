package de.vesterion.vistierie.provider;

import java.util.List;

public interface LlmProvider {
    String name();
    ProviderResponse complete(ProviderRequest req);
    ProviderResponse vision(String model, int maxTokens, String mediaType, String base64, String prompt);

    // Slice 4 — batch processing.
    // Default implementations throw, so providers that don't support batching
    // (e.g. Mock) keep compiling. AnthropicProvider overrides these in Task 4.
    default BatchSubmission submitBatch(List<BatchItem> items) {
        throw new UnsupportedOperationException(name() + " does not support batches");
    }

    default BatchStatus getBatch(String anthropicBatchId) {
        throw new UnsupportedOperationException(name() + " does not support batches");
    }

    default java.util.stream.Stream<BatchResult> streamResults(String resultsUrl) {
        throw new UnsupportedOperationException(name() + " does not support batches");
    }

    class ProviderException extends RuntimeException {
        private final int statusCode;
        private final String errorCode;
        public ProviderException(int statusCode, String errorCode, String msg) {
            super(msg); this.statusCode = statusCode; this.errorCode = errorCode;
        }
        public int statusCode() { return statusCode; }
        public String errorCode() { return errorCode; }
    }
}
