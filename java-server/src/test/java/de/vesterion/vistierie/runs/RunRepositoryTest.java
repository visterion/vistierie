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
}
