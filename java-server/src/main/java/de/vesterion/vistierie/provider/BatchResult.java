package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import tools.jackson.databind.JsonNode;

public record BatchResult(
        String customId,
        String type,                            // "succeeded" | "errored" | "canceled" | "expired"
        String text,                            // present when succeeded
        String stopReason,                      // present when succeeded
        Usage usage,                            // present when succeeded
        String model,                           // present when succeeded
        JsonNode contentBlocks,                 // present when succeeded
        String errorMessage                     // present when errored
) {}
