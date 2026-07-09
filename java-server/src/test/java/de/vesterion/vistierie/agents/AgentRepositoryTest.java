package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRepositoryTest extends PostgresTestBase {
    @Autowired AgentRepository repo;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test void insertAndFindByName() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var name = "bee-isolation";
        var tools = JsonNodeFactory.instance.arrayNode();
        var schema = JsonNodeFactory.instance.objectNode();
        repo.insert(UUID.randomUUID(), tenantId, name,
                "system-prompt", "bee-isolation",
                tools, schema, 12, 180, "wt-token", false, null, null, null, null, null, null);

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
        repo.insert(id, tenantId, "a", "p1", "purpose", tools, null, 5, 60, "t", false, null, null, null, null, null, null);

        repo.replace(id, "p2", "purpose2", tools, null, 6, 90, "t", false, null, null, null, null, null, null);

        var a = repo.findById(id).orElseThrow();
        assertThat(a.systemPrompt()).isEqualTo("p2");
        assertThat(a.modelPurpose()).isEqualTo("purpose2");
        assertThat(a.version()).isEqualTo(2);
    }

    @Test void uniquePerTenantName() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var tools = JsonNodeFactory.instance.arrayNode();
        repo.insert(UUID.randomUUID(), tenantId, "dup", "p", "purpose", tools, null, 5, 60, "t", false, null, null, null, null, null, null);
        try {
            repo.insert(UUID.randomUUID(), tenantId, "dup", "p", "purpose", tools, null, 5, 60, "t", false, null, null, null, null, null, null);
            org.junit.jupiter.api.Assertions.fail("expected unique violation");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) { }
    }

    @Test void findByTenant() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var tools = JsonNodeFactory.instance.arrayNode();
        repo.insert(UUID.randomUUID(), tenantId, "a", "p", "purpose", tools, null, 5, 60, "t", false, null, null, null, null, null, null);
        repo.insert(UUID.randomUUID(), tenantId, "b", "p", "purpose", tools, null, 5, 60, "t", false, null, null, null, null, null, null);
        assertThat(repo.findByTenant(tenantId)).extracting(Agent::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void findScheduledIgnoresUnscheduledAndPaused() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");

        repo.insert(UUID.randomUUID(), tenantId, "unscheduled", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null, null, null, null, null, null);
        var schedId = UUID.randomUUID();
        repo.insert(schedId, tenantId, "scheduled", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null, null, null, null, null, null);
        var pausedSchedId = UUID.randomUUID();
        repo.insert(pausedSchedId, tenantId, "paused-scheduled", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", true, null, null, null, null, null, null);
        // Set schedule via direct SQL — repository doesn't expose it yet at this point
        jdbc.sql("UPDATE vistierie.agents SET schedule='0 * * * * *' WHERE id IN (?, ?)")
                .params(schedId, pausedSchedId).update();

        var found = repo.findScheduled().stream()
                .filter(a -> a.tenantId().equals(tenantId))
                .toList();
        assertThat(found).extracting(Agent::name).containsExactly("scheduled");
    }

    @Test
    void updateLastTickWritesTimestamp() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var id = UUID.randomUUID();
        repo.insert(id, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null, null, null, null, null, null);

        var ts = Instant.parse("2026-05-08T12:34:56Z");
        repo.updateLastTick(id, ts);

        var got = repo.findById(id).orElseThrow().lastTickAt();
        assertThat(got).isEqualTo(ts);
    }

    @Test
    void completionWebhookFieldsRoundTrip() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var id = UUID.randomUUID();
        repo.insert(id, tenantId, "cw-agent", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false, null,
                "https://example.invalid/cb", "secret-cb-token", null, null, null);

        var a = repo.findById(id).orElseThrow();
        assertThat(a.completionWebhook()).isEqualTo("https://example.invalid/cb");
        assertThat(a.completionWebhookToken()).isEqualTo("secret-cb-token");

        // Verify the fields survive a replace as well
        repo.replace(id, "p2", "summarize_cell", mapper.createArrayNode(), null,
                3, 30, "wt", false, null,
                "https://example.invalid/cb2", "secret-cb-token-2", null, null, null);

        var updated = repo.findById(id).orElseThrow();
        assertThat(updated.completionWebhook()).isEqualTo("https://example.invalid/cb2");
        assertThat(updated.completionWebhookToken()).isEqualTo("secret-cb-token-2");
    }

    @Test
    void mcpCredentialsRoundTripThroughJsonb() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var id = UUID.randomUUID();
        var tools = JsonNodeFactory.instance.arrayNode();
        var creds = mapper.createObjectNode().put("http://agora:8080", "tok-123");
        repo.insert(id, tenantId, "mcp-agent", "p", "purpose", tools, null,
                5, 60, null, "wt", false, null, null, null, null, null, null, creds);

        var a = repo.findById(id).orElseThrow();
        assertThat(a.mcpCredentials()).isEqualTo(creds);
        assertThat(a.mcpCredentials().path("http://agora:8080").asText()).isEqualTo("tok-123");

        var updatedCreds = mapper.createObjectNode().put("http://agora:9090", "tok-456");
        repo.replace(id, "p2", "purpose", tools, null, 5, 60, null, "wt", false, null,
                null, null, null, null, null, updatedCreds);

        var updated = repo.findById(id).orElseThrow();
        assertThat(updated.mcpCredentials()).isEqualTo(updatedCreds);
    }

    @Test
    void mcpCredentialsDefaultsToEmptyObjectForBackwardCompatibility() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var id = UUID.randomUUID();
        var tools = JsonNodeFactory.instance.arrayNode();
        // Backward-compatible overload without mcp_credentials — proves existing call sites
        // (and pre-migration rows) still default to an empty object.
        repo.insert(id, tenantId, "legacy-agent", "p", "purpose", tools, null,
                5, 60, "wt", false, null, null, null, null, null, null);

        var a = repo.findById(id).orElseThrow();
        assertThat(a.mcpCredentials()).isEqualTo(mapper.createObjectNode());
        assertThat(a.mcpCredentials().isEmpty()).isTrue();
    }
}
