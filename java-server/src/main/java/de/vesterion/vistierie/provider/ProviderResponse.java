package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;

public record ProviderResponse(String text, String stopReason, Usage usage, String model) {}
