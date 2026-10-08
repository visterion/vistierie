package de.vesterion.vistierie.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(OutputCaptureExtension.class)
class PriceTableTest {
    PriceTable table = new PriceTable(1.0);

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
        var t = new PriceTable(1.0);
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

    @Test
    void opus5HasTheCurrentRates() {
        var t = new PriceTable(1.0);
        // 5 $ In / 25 $ Out bei fixem Kurs 0,92
        assertThat(t.costMicros("claude-opus-5", new Usage(1_000_000, 0, 0, 0))).isEqualTo(4_600_000L);
        assertThat(t.costMicros("claude-opus-5", new Usage(0, 1_000_000, 0, 0))).isEqualTo(23_000_000L);
    }

    @Test
    void sonnet5IsPricedAtAll() {
        // Vorher fehlte das Modell komplett — 751 Calls ohne Shadow-Cost, obwohl es die
        // Prioritaets-1000-Default-Regel ALLER drei Tenants ist. 2 $ / 10 $ ist der Dauerpreis
        // (Probe 2026-10-08: (2, 4, 0, 28172) -> costUSD 0.0056784).
        var t = new PriceTable(1.0);
        assertThat(t.costMicros("claude-sonnet-5", new Usage(1_000_000, 0, 0, 0))).isEqualTo(1_840_000L);
        assertThat(t.costMicros("claude-sonnet-5", new Usage(0, 1_000_000, 0, 0))).isEqualTo(9_200_000L);
    }

    @Test
    void opus47And48UseTheCorrectedRatesIncludingCacheRead() {
        var t = new PriceTable(1.0);
        for (String m : new String[]{"claude-opus-4-7", "claude-opus-4-8"}) {
            assertThat(t.costMicros(m, new Usage(1_000_000, 0, 0, 0))).isEqualTo(4_600_000L);
            assertThat(t.costMicros(m, new Usage(0, 1_000_000, 0, 0))).isEqualTo(23_000_000L);
            // DAS ist die Falle: die Runde-6-Fassung nannte nur den Cache-WRITE-Wert.
            // Cache-Read ist 0,1x = 0,50 $ = 460_000, nicht 1_380_000.
            assertThat(t.costMicros(m, new Usage(0, 0, 0, 1_000_000))).isEqualTo(460_000L);
        }
    }

    @Test
    void everyAnthropicCacheWriteIsTwiceTheInputRate() {
        // Die deployte CLI nutzt AUSSCHLIESSLICH die 1-Stunden-TTL (3,74 Mio Tokens gegen 0 auf
        // 5 Min). Anthropic berechnet die mit 2x, nicht 1,25x. Die Tabelle stand durchgehend
        // auf 1,25x — Altbestand-Bug im gesamten Block.
        var t = new PriceTable(1.0);
        for (String m : new String[]{"claude-haiku-4-5", "claude-sonnet-4-6",
                                     "claude-opus-4-7", "claude-opus-4-8",
                                     "claude-opus-5", "claude-sonnet-5"}) {
            long in = t.costMicros(m, new Usage(1_000_000, 0, 0, 0));
            long cw = t.costMicros(m, new Usage(0, 0, 1_000_000, 0));
            assertThat(cw).as("cache-write for %s", m).isEqualTo(in * 2);
        }
    }

    @Test
    void bedrockPrefixedOpus5Normalizes() {
        var t = new PriceTable(1.0);
        assertThat(t.costMicros("eu.anthropic.claude-opus-5", new Usage(1_000_000, 0, 0, 0)))
                .isEqualTo(4_600_000L);
    }

    /**
     * Oracle: the CLI's own result.modelUsage[*].costUSD (costBasis "list"), measured on the bridge
     * on 2026-10-08 (spec §5). EUR micros / 0.92 must land within 1e-6 USD of it.
     */
    private void assertMatchesCliCost(String model, int in, int out, int cacheWrite, int cacheRead,
                                      double cliCostUsd) {
        long eurMicros = table.costMicros(model, new Usage(in, out, cacheWrite, cacheRead));
        double usd = eurMicros / 0.92 / 1_000_000d;
        assertThat(usd).as("%s (%d, %d, %d, %d)", model, in, out, cacheWrite, cacheRead)
                .isCloseTo(cliCostUsd, within(1e-6));
    }

    @Test
    void matchesTheCliCostUsdOracleOnAllFiveMeasuredTuples() {
        assertMatchesCliCost("claude-opus-5-5",   4, 290, 3984, 28101, 0.0433082);
        assertMatchesCliCost("claude-opus-5-5",   4, 177, 5658, 26393, 0.0540986);
        assertMatchesCliCost("claude-sonnet-5-5", 4,  60, 5527, 27174, 0.0281508);
        assertMatchesCliCost("claude-haiku-5-5",  2,   4, 6186, 10869, 0.00134809);
        assertMatchesCliCost("claude-sonnet-5",   2,   4,    0, 28172, 0.0056784);
    }

    @Test
    void fiveFiveModelsHaveTheirRates() {
        var mtok = 1_000_000;
        assertThat(table.costMicros("claude-opus-5-5",   new Usage(mtok, 0, 0, 0))).isEqualTo(3_680_000L);
        assertThat(table.costMicros("claude-opus-5-5",   new Usage(0, mtok, 0, 0))).isEqualTo(18_400_000L);
        assertThat(table.costMicros("claude-opus-5-5",   new Usage(0, 0, mtok, 0))).isEqualTo(7_360_000L);
        assertThat(table.costMicros("claude-opus-5-5",   new Usage(0, 0, 0, mtok))).isEqualTo(184_000L);
        assertThat(table.costMicros("claude-sonnet-5-5", new Usage(mtok, 0, 0, 0))).isEqualTo(1_840_000L);
        assertThat(table.costMicros("claude-sonnet-5-5", new Usage(0, mtok, 0, 0))).isEqualTo(9_200_000L);
        assertThat(table.costMicros("claude-sonnet-5-5", new Usage(0, 0, mtok, 0))).isEqualTo(3_680_000L);
        assertThat(table.costMicros("claude-sonnet-5-5", new Usage(0, 0, 0, mtok))).isEqualTo(184_000L);
        assertThat(table.costMicros("claude-haiku-5-5",  new Usage(mtok, 0, 0, 0))).isEqualTo(92_000L);
        assertThat(table.costMicros("claude-haiku-5-5",  new Usage(0, mtok, 0, 0))).isEqualTo(460_000L);
        assertThat(table.costMicros("claude-haiku-5-5",  new Usage(0, 0, mtok, 0))).isEqualTo(184_000L);
        assertThat(table.costMicros("claude-haiku-5-5",  new Usage(0, 0, 0, mtok))).isEqualTo(9_200L);
    }

    @Test
    void sonnet5CacheReadIsTwentyCents() {
        assertThat(table.costMicros("claude-sonnet-5", new Usage(0, 0, 0, 1_000_000))).isEqualTo(184_000L);
        assertThat(table.costMicros("claude-sonnet-5", new Usage(0, 0, 1_000_000, 0))).isEqualTo(3_680_000L);
    }

    @Test
    void subscriptionDatedIdNormalizes() {
        // The subscription serves claude-haiku-4-5 as claude-haiku-4-5-20251001 (spec review M1).
        var u = new Usage(1_000_000, 0, 0, 0);
        assertThat(table.costMicros("claude-haiku-4-5-20251001", u))
                .isEqualTo(table.costMicros("claude-haiku-4-5", u));
        // The Bedrock "-YYYYMMDD-vN:N" suffix still normalizes as before.
        assertThat(table.costMicros("eu.anthropic.claude-haiku-4-5-20251001-v1:0", u))
                .isEqualTo(table.costMicros("claude-haiku-4-5", u));
    }

    @Test
    void costMicrosOrNullPricesKnownAndNullsUnknown() {
        assertThat(table.costMicrosOrNull("claude-opus-5-5", new Usage(1_000_000, 0, 0, 0)))
                .isEqualTo(3_680_000L);
        assertThat(table.costMicrosOrNull("opus", new Usage(1, 1, 0, 0))).isNull();
        assertThat(table.costMicrosOrNull(null, new Usage(1, 1, 0, 0))).isNull();
    }

    @Test
    void unpricedModelWarnsExactlyOnce(CapturedOutput output) {
        var t = new PriceTable(1.0);
        for (int i = 0; i < 3; i++) {
            assertThat(t.costMicrosOrNull("synthetic-unpriced-model", new Usage(1, 1, 0, 0))).isNull();
        }
        int count = output.getOut().split("shadow cost: unpriced model synthetic-unpriced-model", -1).length - 1;
        assertThat(count).isEqualTo(1);
    }
}
