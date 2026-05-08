package de.vesterion.vistierie.kill;

import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class KillSwitchService {

    public static class KilledException extends RuntimeException {
        private final String reason;
        private final Instant until;
        public KilledException(String reason, Instant until) {
            super("tenant killed");
            this.reason = reason; this.until = until;
        }
        public String reason() { return reason; }
        public Instant until() { return until; }
    }

    private final TenantRepository repo;
    private final Clock clock;

    public KillSwitchService(TenantRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public void check(UUID tenantId) {
        var t = repo.findById(tenantId).orElseThrow();
        if (t.killUntil() != null && t.killUntil().isAfter(clock.instant())) {
            throw new KilledException(t.killReason(), t.killUntil());
        }
    }
}
