package de.vesterion.vistierie.routing;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingRuleRepositoryTest extends PostgresTestBase {

    @Autowired RoutingRuleRepository repo;
    @Autowired TenantRepository tenants;

    UUID tenantId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "test-" + tenantId.toString().substring(0, 8), "x");
    }

    private RoutingRule rule(String realm, String purpose, int priority) {
        var now = Instant.now();
        return new RoutingRule(UUID.randomUUID(), tenantId, realm, purpose,
                "anthropic", "claude-sonnet-4-6", priority, false, false, now, now);
    }

    @Test
    void insertAndFindById() {
        var r = rule(null, null, 1000);
        repo.insert(r);
        assertThat(repo.findById(r.id())).get().extracting(RoutingRule::priority).isEqualTo(1000);
    }

    @Test
    void uniqueOnTenantRealmPurposePrevents_exactDuplicate() {
        var r1 = rule("medical", "summarize", 10);
        repo.insert(r1);
        var r2 = rule("medical", "summarize", 20);
        assertThatThrownBy(() -> repo.insert(r2))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByTenantOrdersByPriorityAsc() {
        repo.insert(rule(null, null, 1000));
        repo.insert(rule("medical", null, 10));
        repo.insert(rule(null, "summarize", 500));

        var all = repo.findByTenant(tenantId);
        assertThat(all).extracting(RoutingRule::priority).containsExactly(10, 500, 1000);
    }

    @Test
    void cascadeDeletesRulesWhenTenantDeleted() {
        repo.insert(rule(null, null, 1000));
        assertThat(repo.countByTenant(tenantId)).isEqualTo(1);
        tenants.delete(tenantId);
        assertThat(repo.countByTenant(tenantId)).isEqualTo(0);
    }

    @Test
    void existsWildcardDistinguishesNullsFromValues() {
        assertThat(repo.existsWildcard(tenantId)).isFalse();
        repo.insert(rule(null, "summarize", 500));
        assertThat(repo.existsWildcard(tenantId)).isFalse();
        repo.insert(rule(null, null, 1000));
        assertThat(repo.existsWildcard(tenantId)).isTrue();
    }
}
