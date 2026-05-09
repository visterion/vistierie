package de.vesterion.vistierie.routing;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingBootstrapTest extends PostgresTestBase {

    @Autowired RoutingBootstrap bootstrap;
    @Autowired RoutingRuleRepository rules;
    @Autowired TenantRepository tenants;

    @Test
    void bootstrapSeedsRulesForExistingTenantOnce() {
        // Seed the tenant the YAML references.
        var existing = tenants.findByName("hivemem");
        UUID id;
        if (existing.isPresent()) {
            id = existing.get().id();
            // Clean any pre-existing rules from prior test runs in this JVM.
            rules.findByTenant(id).forEach(r -> rules.delete(r.id()));
        } else {
            id = UUID.randomUUID();
            tenants.insert(id, "hivemem", "x");
        }

        bootstrap.run(null);
        var first = rules.countByTenant(id);
        assertThat(first).isGreaterThan(0);

        bootstrap.run(null);
        var second = rules.countByTenant(id);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void bootstrapSkipsUnknownTenantsInYaml() {
        // No assertion beyond "did not throw"; the YAML has tenants that may
        // not be inserted into the test DB.
        bootstrap.run(null);
    }
}
