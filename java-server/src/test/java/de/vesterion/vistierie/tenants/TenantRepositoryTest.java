package de.vesterion.vistierie.tenants;

import de.vesterion.vistierie.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRepositoryTest extends PostgresTestBase {
    @Autowired TenantRepository repo;

    @Test void insertAndFindByName() {
        var id = UUID.randomUUID();
        var name = "tn-" + UUID.randomUUID();
        repo.insert(id, name, "hash-x");
        var t = repo.findByName(name).orElseThrow();
        assertThat(t.id()).isEqualTo(id);
        assertThat(t.name()).isEqualTo(name);
        assertThat(t.tokenHash()).isEqualTo("hash-x");
        assertThat(t.killUntil()).isNull();
    }

    @Test void setKill() {
        var id = UUID.randomUUID();
        var name = "tn-" + UUID.randomUUID();
        repo.insert(id, name, "hash-y");
        repo.setKill(id, Instant.parse("2099-01-01T00:00:00Z"), "test", "operator");
        var t = repo.findByName(name).orElseThrow();
        assertThat(t.killUntil()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
        assertThat(t.killReason()).isEqualTo("test");
        assertThat(t.killSetBy()).isEqualTo("operator");
    }

    @Test void clearKill() {
        var id = UUID.randomUUID();
        var name = "tn-" + UUID.randomUUID();
        repo.insert(id, name, "h");
        repo.setKill(id, Instant.parse("2099-01-01T00:00:00Z"), "r", "o");
        repo.clearKill(id);
        assertThat(repo.findByName(name).orElseThrow().killUntil()).isNull();
    }
}
