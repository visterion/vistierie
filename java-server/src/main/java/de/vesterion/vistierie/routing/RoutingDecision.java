package de.vesterion.vistierie.routing;

public record RoutingDecision(String provider, String model, boolean allowOverride,
                              String fallbackProvider, String fallbackModel, String effort) {
    /** Compatibility constructor: decision without effort. */
    public RoutingDecision(String provider, String model, boolean allowOverride,
                           String fallbackProvider, String fallbackModel) {
        this(provider, model, allowOverride, fallbackProvider, fallbackModel, null);
    }

    /** Compatibility constructor: decision without fallback. */
    public RoutingDecision(String provider, String model, boolean allowOverride) {
        this(provider, model, allowOverride, null, null, null);
    }
}
