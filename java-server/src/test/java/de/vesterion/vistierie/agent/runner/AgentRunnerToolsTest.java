package de.vesterion.vistierie.agent.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class AgentRunnerToolsTest extends PostgresTestBase {

    static WireMockServer wm;

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    @BeforeEach void up() {
        if (wm == null) { wm = new WireMockServer(0); wm.start(); }
        configureFor("localhost", wm.port());
        wm.resetAll();
        stub.resetAll();
    }
    @AfterEach void resetWm() { wm.resetAll(); }

    private void registerRouting(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test void toolUseRoundTripWithThreeParallelTools() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantId);

        stubFor(post(urlEqualTo("/tools/cell.search")).willReturn(okJson("{\"output\":{\"hits\":3}}")));
        stubFor(post(urlEqualTo("/tools/cell.read")).willReturn(okJson("{\"output\":{\"text\":\"abc\"}}")));
        stubFor(post(urlEqualTo("/tools/cell.tag")).willReturn(okJson("{\"output\":{\"tagged\":true}}")));

        var tools = mapper.createArrayNode();
        for (var t : List.of(
                Map.of("name","cell.search","description","s","input_schema",Map.of("type","object"),
                       "webhook_url","http://localhost:" + wm.port() + "/tools/cell.search"),
                Map.of("name","cell.read","description","r","input_schema",Map.of("type","object"),
                       "webhook_url","http://localhost:" + wm.port() + "/tools/cell.read"),
                Map.of("name","cell.tag","description","t","input_schema",Map.of("type","object"),
                       "webhook_url","http://localhost:" + wm.port() + "/tools/cell.tag"))) {
            tools.add(mapper.valueToTree(t));
        }
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "explorer", "you explore", "summarize_cell",
                tools, schema, 5, 60, "wt-tok", false, null);
        budgetFixtures.seed(tenantId, agentId);

        stub.script(
                StubLlmScripts.Turn.toolUses(
                        StubLlmScripts.Turn.toolUse("cell.search", Map.of("q","x")),
                        StubLlmScripts.Turn.toolUse("cell.read",   Map.of("id","c1")),
                        StubLlmScripts.Turn.toolUse("cell.tag",    Map.of("id","c1","tag","new"))),
                StubLlmScripts.Turn.endTurn("{\"x\":\"final\"}")
        );

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);
        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.output().path("x").asText()).isEqualTo("final");
        verify(1, postRequestedFor(urlEqualTo("/tools/cell.search")));
        verify(1, postRequestedFor(urlEqualTo("/tools/cell.read")));
        verify(1, postRequestedFor(urlEqualTo("/tools/cell.tag")));
    }

    @Test void toolFailureFailsRun() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantId);
        stubFor(post(urlEqualTo("/tools/x")).willReturn(serverError()));
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name","x","description","x","input_schema",Map.of("type","object"),
                "webhook_url","http://localhost:" + wm.port() + "/tools/x")));
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "ax", "p", "summarize_cell",
                tools, null, 3, 60, "wt", false, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("x", Map.of())));
        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);
        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).contains("tool_error");
    }
}
