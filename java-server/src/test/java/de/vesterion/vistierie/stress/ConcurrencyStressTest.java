package de.vesterion.vistierie.stress;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingConfig;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("stress")
@ActiveProfiles("test-stub-llm")
class ConcurrencyStressTest extends PostgresTestBase {

    @Autowired AgentDispatcher dispatcher;
    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingConfig routingConfig;

    @Test void hundredParallelTopLevelRuns() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var t = new RoutingConfig.TenantRouting();
        t.setPurposes(new HashMap<>());
        var rule = new RoutingConfig.Rule();
        rule.setProvider("anthropic");
        rule.setModel("claude-haiku-4-5");
        rule.setAllowOverride(false);
        t.getPurposes().put("summarize_cell", rule);
        t.setDefault(rule);
        routingConfig.getTenants().put(tenantName, t);

        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "stress", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 60, "wt", false);

        var script = new ArrayList<StubLlmScripts.ScriptedTurn>();
        for (int i = 0; i < 100; i++) script.add(StubLlmScripts.Turn.endTurn("{\"x\":\"v" + i + "\"}"));
        stub.script(script.toArray(new StubLlmScripts.ScriptedTurn[0]));

        var agent = agents.findById(agentId).orElseThrow();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            ids.add(dispatcher.trigger(tenantId, agent, "manual",
                    mapper.createObjectNode(), null, null));
        }

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            int done = 0;
            for (String id : ids) {
                if (runs.findById(id).map(r -> "done".equals(r.status())).orElse(false)) done++;
            }
            assertThat(done).isEqualTo(100);
        });
    }
}
