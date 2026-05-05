package de.vesterion.vistierie.kill;

import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.stereotype.Component;

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

    public KillSwitchService(TenantRepository repo) { this.repo = repo; }

    public void check(UUID tenantId) {
        var t = repo.findById(tenantId).orElseThrow();
        if (t.killUntil() != null && t.killUntil().isAfter(Instant.now())) {
            throw new KilledException(t.killReason(), t.killUntil());
        }
    }
}
