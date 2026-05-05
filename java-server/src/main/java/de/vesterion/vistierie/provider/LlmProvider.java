package de.vesterion.vistierie.provider;

public interface LlmProvider {
    String name();
    ProviderResponse complete(ProviderRequest req);
    ProviderResponse vision(String model, int maxTokens, String mediaType, String base64, String prompt);

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
