package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderResponseTest {

    @Test void oldOverloadsLeaveTelemetryNull() {
        var r4 = new ProviderResponse("t", "end_turn", new Usage(1, 2, 0, 0), "m");
        var r6 = new ProviderResponse("t", "end_turn", new Usage(1, 2, 0, 0), "m", null, "s-1");
        assertThat(r4.servedModel()).isNull();
        assertThat(r4.rateLimit()).isNull();
        assertThat(r6.servedModel()).isNull();
        assertThat(r6.rateLimit()).isNull();
        assertThat(r6.sessionId()).isEqualTo("s-1");
    }

    @Test void blankServedModelBecomesNull() {
        var r = new ProviderResponse("t", "end_turn", new Usage(0, 0, 0, 0), "m", null, null, "  ", null);
        assertThat(r.servedModel()).isNull();
    }
}
