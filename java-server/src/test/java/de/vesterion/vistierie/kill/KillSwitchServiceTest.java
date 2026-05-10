package de.vesterion.vistierie.kill;

import de.vesterion.vistierie.auth.AuthExceptions;
import de.vesterion.vistierie.tenants.Tenant;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KillSwitchServiceTest {

    private final TenantRepository repo = mock(TenantRepository.class);
    private final Instant now = Instant.parse("2026-05-10T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final KillSwitchService svc = new KillSwitchService(repo, clock);

    private Tenant tenant(Instant killUntil, String reason) {
        return new Tenant(UUID.randomUUID(), "tn", "h", killUntil, reason, "admin",
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test void passesThroughWhenKillUntilIsNull() {
        var t = tenant(null, null);
        when(repo.findById(eq(t.id()))).thenReturn(Optional.of(t));
        svc.check(t.id()); // no throw
    }

    @Test void throwsWhenKillWindowIsActive() {
        var until = now.plusSeconds(60);
        var t = tenant(until, "abuse");
        when(repo.findById(eq(t.id()))).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> svc.check(t.id()))
                .isInstanceOf(KillSwitchService.KilledException.class)
                .satisfies(e -> {
                    var ke = (KillSwitchService.KilledException) e;
                    assertThat(ke.reason()).isEqualTo("abuse");
                    assertThat(ke.until()).isEqualTo(until);
                });
    }

    @Test void passesThroughWhenKillWindowHasExpired() {
        var t = tenant(now.minusSeconds(1), "old");
        when(repo.findById(eq(t.id()))).thenReturn(Optional.of(t));
        svc.check(t.id()); // no throw
    }

    @Test void passesThroughExactlyAtBoundary() {
        // killUntil == clock.instant() ⇒ isAfter is false ⇒ allowed
        var t = tenant(now, "edge");
        when(repo.findById(eq(t.id()))).thenReturn(Optional.of(t));
        svc.check(t.id());
    }

    @Test void throwsWhenTenantMissing() {
        var id = UUID.randomUUID();
        when(repo.findById(eq(id))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.check(id))
                .isInstanceOf(AuthExceptions.Unauthorized.class)
                .hasMessageContaining("tenant no longer exists");
    }
}
