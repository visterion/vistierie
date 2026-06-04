package de.vesterion.vistierie.provider;

import java.util.List;

public interface LlmProvider {
    String name();
    ProviderResponse complete(ProviderRequest req);
    ProviderResponse vision(String model, int maxTokens, String mediaType, String base64, String prompt);

    /** One image for a multi-image vision call: base64 payload + its media type (e.g. image/png). */
    record ImageInput(String mediaType, String base64) {}

    /** Multi-image vision: N images + a prompt in one model call. Default throws so providers that
     *  don't implement it keep compiling (mirrors the batch defaults). */
    default ProviderResponse visionMulti(String model, int maxTokens, List<ImageInput> images, String prompt) {
        throw new UnsupportedOperationException(name() + " does not support multi-image vision");
    }

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
