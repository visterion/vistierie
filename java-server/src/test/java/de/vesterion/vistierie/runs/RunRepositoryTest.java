package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunRepositoryTest extends PostgresTestBase {
    @Autowired RunRepository runs;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper mapper;

    @Test void insertAndUpdateLifecycle() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "purpose",
                JsonNodeFactory.instance.arrayNode(), null, 5, 60, "wt", false);

        var runId = "01J" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
        var snapshot = mapper.readTree("{\"version\":1,\"name\":\"a\"}");
        runs.insert(runId, tenantId, agentId, snapshot, 1, null,
                "manual", "queued", null, null, null);

        var r = runs.findById(runId).orElseThrow();
        assertThat(r.status()).isEqualTo("queued");

        runs.markRunning(runId);
        runs.appendMessages(runId, mapper.readTree("[{\"role\":\"user\",\"content\":\"hi\"}]"));

        var r2 = runs.findById(runId).orElseThrow();
        assertThat(r2.status()).isEqualTo("running");
        assertThat(r2.messagesSnapshot().size()).isEqualTo(1);

        runs.markTerminal(runId, "done", mapper.readTree("{\"ok\":true}"), null, "done summary");
        var r3 = runs.findById(runId).orElseThrow();
        assertThat(r3.status()).isEqualTo("done");
        assertThat(r3.output().get("ok").asBoolean()).isTrue();
        assertThat(r3.finishedAt()).isNotNull();
    }

    @Test
    void hasOpenRunAndLatestOpenRunId() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), null, 3, 30, "wt", false);

        assertThat(runs.hasOpenRun(agentId)).isFalse();
        assertThat(runs.latestOpenRunId(agentId)).isEmpty();

        var snap = mapper.createObjectNode();
        runs.insert("R1", tenantId, agentId, snap, 1, null, "manual", "queued",
                mapper.createObjectNode(), null, null);
        assertThat(runs.hasOpenRun(agentId)).isTrue();
        assertThat(runs.latestOpenRunId(agentId)).contains("R1");

        runs.markRunning("R1");
        runs.insert("R2", tenantId, agentId, snap, 1, null, "manual", "queued",
                mapper.createObjectNode(), null, null);
        // R2 is queued, R1 is running — both open. latest by started_at desc, NULLS LAST: R1 has started_at, R2 doesn't.
        assertThat(runs.latestOpenRunId(agentId)).contains("R1");

        runs.markTerminal("R1", "done", null, null, null);
        runs.markTerminal("R2", "done", null, null, null);
        assertThat(runs.hasOpenRun(agentId)).isFalse();
    }
}
