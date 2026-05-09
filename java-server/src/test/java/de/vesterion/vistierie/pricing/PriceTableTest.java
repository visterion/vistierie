package de.vesterion.vistierie.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceTableTest {
    PriceTable table = new PriceTable();

    @Test void haikuPrice() {
        var u = new Usage(1_000_000, 1_000_000, 0, 0);
        long micros = table.costMicros("claude-haiku-4-5", u);
        assertThat(micros).isPositive();
    }

    @Test void unknownModelThrows() {
        assertThatThrownBy(() -> table.costMicros("ghost", new Usage(0, 0, 0, 0)))
                .isInstanceOf(PriceTable.UnknownModelException.class);
    }

    @Test void cacheRatesApplied() {
        var noCache  = table.costMicros("claude-haiku-4-5", new Usage(1_000, 0, 0, 0));
        var fromCache = table.costMicros("claude-haiku-4-5", new Usage(0, 0, 0, 1_000));
        assertThat(fromCache).isLessThan(noCache);
    }

    @Test
    void batchPricingIsHalf() {
        var t = new PriceTable();
        var u = new de.vesterion.vistierie.pricing.Usage(1000, 200, 0, 0);
        var standard = t.costMicros("claude-haiku-4-5", u);
        assertThat(t.costMicrosBatch("claude-haiku-4-5", u))
                .isEqualTo(standard / 2L);
    }

    @Test void openAiModelsKnown() {
        var u = new Usage(1_000_000, 1_000_000, 0, 0);
        assertThat(table.costMicros("gpt-4o-mini", u)).isEqualTo(690_000L);
        assertThat(table.costMicros("gpt-4o", u)).isEqualTo(11_500_000L);
        assertThat(table.costMicros("gpt-5", u)).isEqualTo(10_350_000L);
        assertThat(table.costMicros("o4-mini", u)).isEqualTo(5_060_000L);
    }

    @Test void xaiModelsKnown() {
        var u = new Usage(1_000_000, 1_000_000, 0, 0);
        assertThat(table.costMicros("grok-4", u)).isEqualTo(16_560_000L);
        assertThat(table.costMicros("grok-4-fast", u)).isEqualTo(644_000L);
        assertThat(table.costMicros("grok-code-fast-1", u)).isEqualTo(1_564_000L);
    }

    @Test void openAiCacheReadHalvesInputCost() {
        var fresh = table.costMicros("gpt-4o", new Usage(1_000_000, 0, 0, 0));
        var cached = table.costMicros("gpt-4o", new Usage(0, 0, 0, 1_000_000));
        assertThat(cached * 2L).isEqualTo(fresh);
    }
}
