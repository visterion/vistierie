package de.vesterion.vistierie.routing;

public record RoutingDecision(String provider, String model, boolean allowOverride,
                              String fallbackProvider, String fallbackModel) {
    /** Compatibility constructor: decision without fallback. */
    public RoutingDecision(String provider, String model, boolean allowOverride) {
        this(provider, model, allowOverride, null, null);
    }
}
