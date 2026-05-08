package de.vesterion.vistierie.pricing;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * EUR-micros (1 EUR = 1_000_000 micros) per million input/output tokens.
 * Source: Anthropic public pricing converted at a fixed 1 USD = 0.92 EUR.
 * When prices change, edit the table and ship a release.
 */
@Component
public class PriceTable {

    private record Rates(long inputPerMtok,
                         long outputPerMtok,
                         long cacheWritePerMtok,
                         long cacheReadPerMtok) {}

    private static final Map<String, Rates> RATES = Map.of(
            "claude-haiku-4-5",  new Rates(  920_000,  4_600_000,  1_150_000,  92_000),
            "claude-sonnet-4-6", new Rates(2_760_000, 13_800_000,  3_450_000, 276_000),
            "claude-opus-4-7",   new Rates(13_800_000, 69_000_000, 17_250_000, 1_380_000)
    );

    public long costMicros(String model, Usage u) {
        var r = RATES.get(model);
        if (r == null) throw new UnknownModelException(model);
        long input  = mul(u.inputTokens(),               r.inputPerMtok());
        long output = mul(u.outputTokens(),              r.outputPerMtok());
        long cwrite = mul(u.cacheCreationInputTokens(),  r.cacheWritePerMtok());
        long cread  = mul(u.cacheReadInputTokens(),      r.cacheReadPerMtok());
        return input + output + cwrite + cread;
    }

    /** Half-price for batched calls per Anthropic Batches pricing. */
    public long costMicrosBatch(String model, Usage u) {
        return costMicros(model, u) / 2L;
    }

    private static long mul(int tokens, long perMtok) {
        return Math.round(((double) tokens / 1_000_000d) * perMtok);
    }

    public static class UnknownModelException extends RuntimeException {
        public UnknownModelException(String m) { super("unknown model " + m); }
    }
}
