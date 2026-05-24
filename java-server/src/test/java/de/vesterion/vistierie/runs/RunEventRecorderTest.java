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

class RunEventRecorderTest extends PostgresTestBase {
    @Autowired RunRepository runs;
    @Autowired RunEventRecorder events;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper mapper;

    @Test void recordsAndOrders() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "purpose",
                JsonNodeFactory.instance.arrayNode(), null, 5, 60, "wt", false, null, null, null);
        var runId = "01J" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
        runs.insert(runId, tenantId, agentId,
                mapper.readTree("{\"version\":1}"), 1, null, "manual", "queued", null, null, null);

        events.record(runId, "info", "turn_started", mapper.readTree("{\"turn\":1}"));
        events.record(runId, "info", "turn_finished", mapper.readTree("{\"turn\":1}"));

        var list = events.byRun(runId);
        assertThat(list).extracting("type").containsExactly("turn_started", "turn_finished");
    }
}
