package de.vesterion.vistierie.agent.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.audit.LlmCallBodyRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import de.vesterion.vistierie.transcript.RunToolCallRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class AgentRunnerTranscriptCaptureTest extends PostgresTestBase {

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
    @Autowired RunToolCallRepository toolCalls;
    @Autowired LlmCallBodyRepository bodies;

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
        routingResolver.bumpVersion();
    }

    @Test void capturesToolCallWithEmptyOutputAndContentBlocks() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRouting(tenantId);

        stubFor(post(urlEqualTo("/tools/finnhub")).willReturn(okJson("{\"output\":{\"count\":0}}")));

        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", "finnhub", "description", "news", "input_schema", Map.of("type", "object"),
                "webhook_url", "http://localhost:" + wm.port() + "/tools/finnhub")));
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "echo", "p", "routine",
                tools, schema, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);

        stub.script(
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("finnhub", Map.of("q", "AAPL"))),
                StubLlmScripts.Turn.endTurn("{\"x\":\"done\"}"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");

        var captured = toolCalls.findByRun(runId);
        assertThat(captured).hasSize(1);
        var tc = captured.get(0);
        assertThat(tc.toolName()).isEqualTo("finnhub");
        assertThat(tc.toolType()).isEqualTo("http");
        assertThat(tc.isError()).isFalse();
        assertThat(tc.input().path("q").asText()).isEqualTo("AAPL");
        assertThat(tc.output().path("count").asInt()).isZero();
        assertThat(tc.llmCallId()).isNotBlank();

        var bodyContent = bodies.findByCallId(tc.llmCallId()).orElseThrow().responseContentJson();
        assertThat(bodyContent.get(0).path("type").asText()).isEqualTo("tool_use");
    }
}
