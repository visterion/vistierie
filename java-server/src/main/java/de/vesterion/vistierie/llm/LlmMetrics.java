package de.vesterion.vistierie.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String provider, String model, String endpoint,
                       String status, long durationMs, long costMicros) {
        var tags = Tags.of(
                "provider", provider,
                "model", model,
                "endpoint", endpoint,
                "status", status);
        registry.counter("vistierie.llm.calls", tags).increment();
        registry.timer("vistierie.llm.latency", tags)
                .record(Duration.ofMillis(durationMs));
        registry.counter("vistierie.llm.cost.micros", tags).increment(costMicros);
    }

    public void recordFallback(String from, String to, String reason) {
        registry.counter("vistierie.llm.fallback",
                Tags.of("from", from, "to", to, "reason", reason)).increment();
    }

    public void recordShadowCost(String provider, String model, String endpoint, long micros) {
        registry.counter("vistierie.llm.shadow.cost.micros",
                Tags.of("provider", provider, "model", model, "endpoint", endpoint))
                .increment(micros);
    }
}
