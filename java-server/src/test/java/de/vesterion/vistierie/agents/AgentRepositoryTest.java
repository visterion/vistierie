package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRepositoryTest extends PostgresTestBase {
    @Autowired AgentRepository repo;
    @Autowired TenantRepository tenants;

    @Test void insertAndFindByName() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var name = "bee-isolation";
        var tools = JsonNodeFactory.instance.arrayNode();
        var schema = JsonNodeFactory.instance.objectNode();
        repo.insert(UUID.randomUUID(), tenantId, name,
                "system-prompt", "bee-isolation",
                tools, schema, 12, 180, "wt-token", false);

        var a = repo.findByName(tenantId, name).orElseThrow();
        assertThat(a.name()).isEqualTo(name);
        assertThat(a.systemPrompt()).isEqualTo("system-prompt");
        assertThat(a.modelPurpose()).isEqualTo("bee-isolation");
        assertThat(a.maxTurns()).isEqualTo(12);
        assertThat(a.maxRunSeconds()).isEqualTo(180);
        assertThat(a.webhookToken()).isEqualTo("wt-token");
        assertThat(a.paused()).isFalse();
        assertThat(a.version()).isEqualTo(1);
    }

    @Test void replaceIncrementsVersion() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var id = UUID.randomUUID();
        var tools = JsonNodeFactory.instance.arrayNode();
        repo.insert(id, tenantId, "a", "p1", "purpose", tools, null, 5, 60, "t", false);

        repo.replace(id, "p2", "purpose2", tools, null, 6, 90, "t", false);

        var a = repo.findById(id).orElseThrow();
        assertThat(a.systemPrompt()).isEqualTo("p2");
        assertThat(a.modelPurpose()).isEqualTo("purpose2");
        assertThat(a.version()).isEqualTo(2);
    }

    @Test void uniquePerTenantName() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var tools = JsonNodeFactory.instance.arrayNode();
        repo.insert(UUID.randomUUID(), tenantId, "dup", "p", "purpose", tools, null, 5, 60, "t", false);
        try {
            repo.insert(UUID.randomUUID(), tenantId, "dup", "p", "purpose", tools, null, 5, 60, "t", false);
            org.junit.jupiter.api.Assertions.fail("expected unique violation");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) { }
    }

    @Test void findByTenant() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var tools = JsonNodeFactory.instance.arrayNode();
        repo.insert(UUID.randomUUID(), tenantId, "a", "p", "purpose", tools, null, 5, 60, "t", false);
        repo.insert(UUID.randomUUID(), tenantId, "b", "p", "purpose", tools, null, 5, 60, "t", false);
        assertThat(repo.findByTenant(tenantId)).extracting(Agent::name).containsExactlyInAnyOrder("a", "b");
    }
}
