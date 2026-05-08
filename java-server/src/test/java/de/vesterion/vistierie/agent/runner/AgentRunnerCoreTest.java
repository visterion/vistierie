package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingConfig;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class AgentRunnerCoreTest extends PostgresTestBase {

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingConfig routingConfig;

    @BeforeEach void resetStub() { stub.resetAll(); }

    private void registerRouting(String tenantName) {
        var t = new RoutingConfig.TenantRouting();
        t.setPurposes(new HashMap<>());
        var rule = new RoutingConfig.Rule();
        rule.setProvider("anthropic");
        rule.setModel("claude-haiku-4-5");
        rule.setAllowOverride(false);
        t.getPurposes().put("summarize_cell", rule);
        t.setDefault(rule);
        routingConfig.getTenants().put(tenantName, t);
    }

    @Test void singleTurnEndsImmediately() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantName);
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                JsonNodeFactory.instance.arrayNode(), schema, 5, 60, "wt", false);
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{\"q\":\"hi\"}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().path("x").asText()).isEqualTo("yes");
    }
}
