package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import tools.jackson.databind.JsonNode;

public record ProviderResponse(
        String text,
        String stopReason,
        Usage usage,
        String model,
        JsonNode contentBlocks
) {
    public ProviderResponse(String text, String stopReason, Usage usage, String model) {
        this(text, stopReason, usage, model, null);
    }
}
