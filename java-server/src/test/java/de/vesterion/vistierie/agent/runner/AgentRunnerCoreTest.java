package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
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

import java.time.Instant;
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
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    @BeforeEach void resetStub() { stub.resetAll(); }

    private void registerRouting(UUID tId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test void singleTurnEndsImmediately() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantId);
        var agentId = UUID.randomUUID();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                JsonNodeFactory.instance.arrayNode(), schema, 5, 60, "wt", false, null);
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"yes\"}"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{\"q\":\"hi\"}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().path("x").asText()).isEqualTo("yes");
    }
}
