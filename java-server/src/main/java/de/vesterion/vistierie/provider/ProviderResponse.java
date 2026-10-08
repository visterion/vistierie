package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import tools.jackson.databind.JsonNode;

/**
 * @param model       what the provider echoed or served; callers record the ROUTED model instead
 * @param servedModel the model id the provider actually served (claude-bridge {@code model}),
 *                    null when unknown — every other provider, or an old bridge
 * @param rateLimit   subscription quota telemetry, null when the provider has none
 */
public record ProviderResponse(
        String text,
        String stopReason,
        Usage usage,
        String model,
        JsonNode contentBlocks,
        String sessionId,
        String servedModel,
        RateLimitInfo rateLimit
) {
    public ProviderResponse {
        if (servedModel != null && servedModel.isBlank()) servedModel = null;
    }

    public ProviderResponse(String text, String stopReason, Usage usage, String model) {
        this(text, stopReason, usage, model, null, null, null, null);
    }

    public ProviderResponse(String text, String stopReason, Usage usage, String model,
                            JsonNode contentBlocks) {
        this(text, stopReason, usage, model, contentBlocks, null, null, null);
    }

    public ProviderResponse(String text, String stopReason, Usage usage, String model,
                            JsonNode contentBlocks, String sessionId) {
        this(text, stopReason, usage, model, contentBlocks, sessionId, null, null);
    }
}
