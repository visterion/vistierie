package de.vesterion.vistierie.provider;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionCooldownTest {

    @Test
    void notCoolingInitially() {
        var cd = new SubscriptionCooldown(3600);
        assertThat(cd.cooling(Instant.parse("2026-07-24T00:00:00Z"))).isFalse();
    }

    @Test
    void coolingAfterOpenUntilExpiry() {
        var cd = new SubscriptionCooldown(3600);
        var t0 = Instant.parse("2026-07-24T00:00:00Z");
        cd.open(t0);
        assertThat(cd.cooling(t0)).isTrue();
        assertThat(cd.cooling(t0.plusSeconds(3599))).isTrue();
        assertThat(cd.cooling(t0.plusSeconds(3600))).isFalse(); // boundary: expired
        assertThat(cd.cooling(t0.plusSeconds(7200))).isFalse();
    }
}
