package de.vesterion.vistierie.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Global, in-memory circuit-breaker for the shared Claude Max subscription. Opened when a
 * subscription call returns 429 subscription_exhausted; while open, {@code LlmService} skips
 * the subscription and routes straight to the fallback provider. Global (not per-tenant)
 * because the Max account is shared across tenants and tiers, so the limit is account-wide.
 * In-memory: resets on restart (acceptable — the limit resets on a rolling window and a
 * post-restart call simply re-opens it).
 */
@Component
public class SubscriptionCooldown {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCooldown.class);

    private final long durationSeconds;
    private volatile Instant coolingUntil = Instant.EPOCH;

    public SubscriptionCooldown(
            @Value("${vistierie.claude-subscription.cooldown-seconds:3600}") long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    /** True while the subscription is cooling as of {@code now}. */
    public boolean cooling(Instant now) {
        return now.isBefore(coolingUntil);
    }

    /** Open (or extend) the cooldown to {@code now + duration}. Idempotent under races. */
    public void open(Instant now) {
        coolingUntil = now.plusSeconds(durationSeconds);
        log.warn("claude-subscription cooldown opened until {} ({}s) after usage-limit 429",
                coolingUntil, durationSeconds);
    }
}
