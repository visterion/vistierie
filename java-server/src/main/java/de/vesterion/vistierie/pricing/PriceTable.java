package de.vesterion.vistierie.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EUR-micros (1 EUR = 1_000_000 micros) per million input/output tokens.
 * Source: Anthropic public pricing converted at a fixed 1 USD = 0.92 EUR
 * (the table values bake in this rate). When the FX rate drifts, configure
 * {@code vistierie.pricing.cost-multiplier} to scale all costs without
 * regenerating the table.
 */
@Component
public class PriceTable {

    private static final Logger log = LoggerFactory.getLogger(PriceTable.class);

    /** Models already reported as unpriced — one WARN per model per process (spec §3.2.5). */
    private final Set<String> warnedUnpriced = ConcurrentHashMap.newKeySet();

    private final double costMultiplier;

    public PriceTable(@Value("${vistierie.pricing.cost-multiplier:1.0}") double costMultiplier) {
        if (costMultiplier <= 0) {
            throw new IllegalArgumentException("cost-multiplier must be > 0");
        }
        this.costMultiplier = costMultiplier;
    }

    private record Rates(long inputPerMtok,
                         long outputPerMtok,
                         long cacheWritePerMtok,
                         long cacheReadPerMtok) {}

    // OpenAI/xAI rates sourced from BerriAI/litellm model_prices_and_context_window.json
    // (https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json),
    // converted USD→EUR at the same fixed 0.92 rate. Verify against the upstream
    // provider pricing page when adding new models.
    private static final Map<String, Rates> RATES = Map.ofEntries(
            // Anthropic. Cache-Write ist 2x (1-Stunden-TTL) — die Claude-CLI fordert
            // ausschliesslich diese TTL an (nachgemessen: 3.735.545 Tokens auf
            // ephemeral_1h, 0 auf ephemeral_5m). Ein kuenftiger Aufrufer mit 5-Min-TTL
            // waere hier um Faktor 1,6 zu hoch bepreist. Cache-Read ist 0,1x.
            Map.entry("claude-haiku-4-5",  new Rates(   920_000,  4_600_000,  1_840_000,    92_000)),
            Map.entry("claude-sonnet-4-6", new Rates( 2_760_000, 13_800_000,  5_520_000,   276_000)),
            Map.entry("claude-opus-4-7",   new Rates( 4_600_000, 23_000_000,  9_200_000,   460_000)),
            Map.entry("claude-opus-4-8",   new Rates( 4_600_000, 23_000_000,  9_200_000,   460_000)),
            Map.entry("claude-opus-5",     new Rates( 4_600_000, 23_000_000,  9_200_000,   460_000)),
            // Sonnet 5: 2 $ / 10 $ ist der Dauerpreis, Cache-Read 0,20 $ (Probe 2026-10-08:
            // (2, 4, 0, 28172) -> costUSD 0.0056784 der CLI).
            Map.entry("claude-sonnet-5",   new Rates( 1_840_000,  9_200_000,  3_680_000,   184_000)),
            // 5.5-Familie, gegen result.modelUsage[*].costUSD der CLI nachgerechnet (Spec §5).
            // Haiku 5.5 kostet fuer Prompts > 100k Tokens mehr; die Tabelle preist nur die
            // <= 100k-Stufe — bekannte Unterschaetzung, bewusst nicht modelliert.
            Map.entry("claude-opus-5-5",   new Rates( 3_680_000, 18_400_000,  7_360_000,   184_000)),
            Map.entry("claude-sonnet-5-5", new Rates( 1_840_000,  9_200_000,  3_680_000,   184_000)),
            Map.entry("claude-haiku-5-5",  new Rates(    92_000,    460_000,    184_000,     9_200)),
            // OpenAI — no cache_creation cost; only cache_read discount.
            Map.entry("gpt-4o",            new Rates( 2_300_000,  9_200_000,          0, 1_150_000)),
            Map.entry("gpt-4o-mini",       new Rates(   138_000,    552_000,          0,    69_000)),
            Map.entry("gpt-5",             new Rates( 1_150_000,  9_200_000,          0,   115_000)),
            Map.entry("gpt-5-mini",        new Rates(   230_000,  1_840_000,          0,    23_000)),
            Map.entry("o4-mini",           new Rates( 1_012_000,  4_048_000,          0,   253_000)),
            // xAI
            Map.entry("grok-4",            new Rates( 2_760_000, 13_800_000,          0,         0)),
            Map.entry("grok-4-fast",       new Rates(   184_000,    460_000,          0,    46_000)),
            Map.entry("grok-code-fast-1",  new Rates(   184_000,  1_380_000,          0,    18_400))
    );

    public long costMicros(String model, Usage u) {
        var r = RATES.get(normalize(model));
        if (r == null) throw new UnknownModelException(model);
        long input  = mul(u.inputTokens(),               r.inputPerMtok());
        long output = mul(u.outputTokens(),              r.outputPerMtok());
        long cwrite = mul(u.cacheCreationInputTokens(),  r.cacheWritePerMtok());
        long cread  = mul(u.cacheReadInputTokens(),      r.cacheReadPerMtok());
        return Math.round((input + output + cwrite + cread) * costMultiplier);
    }

    /** Half-price for batched calls per Anthropic Batches pricing. */
    public long costMicrosBatch(String model, Usage u) {
        return costMicros(model, u) / 2L;
    }

    /**
     * {@link #costMicros} for audit figures that may stay empty: null for an unpriced (or null)
     * model instead of throwing. Logs one WARN per model per process so a new model id the CLI
     * starts serving is visible instead of silently producing null shadow costs.
     */
    public Long costMicrosOrNull(String model, Usage u) {
        if (model == null) return null;
        try {
            return costMicros(model, u);
        } catch (UnknownModelException e) {
            if (warnedUnpriced.add(model)) {
                log.warn("shadow cost: unpriced model {}", model);
            }
            return null;
        }
    }

    /**
     * Strips Bedrock inference-profile prefixes and version suffixes so that
     * e.g. "eu.anthropic.claude-haiku-4-5-20251001-v1:0" maps to "claude-haiku-4-5", and the
     * subscription's dated id "claude-haiku-4-5-20251001" maps to "claude-haiku-4-5".
     */
    private static String normalize(String model) {
        String m = model;
        for (String prefix : new String[]{"eu.anthropic.", "global.anthropic.", "anthropic."}) {
            if (m.startsWith(prefix)) { m = m.substring(prefix.length()); break; }
        }
        // strip -YYYYMMDD-vN:N Bedrock version suffix
        m = m.replaceAll("-\\d{8}-v\\d+:\\d+$", "");
        // strip a bare -YYYYMMDD date suffix (subscription-served ids)
        m = m.replaceAll("-\\d{8}$", "");
        return m;
    }

    private static long mul(int tokens, long perMtok) {
        return Math.round(((double) tokens / 1_000_000d) * perMtok);
    }

    public static class UnknownModelException extends RuntimeException {
        public UnknownModelException(String m) { super("unknown model " + m); }
    }
}
