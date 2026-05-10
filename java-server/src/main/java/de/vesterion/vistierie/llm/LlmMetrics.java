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
}
