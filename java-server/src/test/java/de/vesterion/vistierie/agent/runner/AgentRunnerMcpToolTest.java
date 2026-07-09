package de.vesterion.vistierie.agent.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class AgentRunnerMcpToolTest extends PostgresTestBase {

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

    @Test void mcpToolCallCompletesRunAndCapturesToolType() throws Exception {
        var fake = new FakeMcpServer("secret-tok", 0);
        try {
            var tenantId = UUID.randomUUID();
            var tenantName = "tn-" + tenantId;
            tenants.insert(tenantId, tenantName, "h");
            registerRouting(tenantId);

            var tools = mapper.createArrayNode();
            tools.add(mapper.valueToTree(Map.of(
                    "name", "echo", "type", "mcp", "description", "echoes",
                    "input_schema", Map.of("type", "object"),
                    "mcp_server_url", fake.baseUrl())));
            var schema = mapper.readTree(
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
            var agentId = UUID.randomUUID();
            var mcpCredentials = mapper.createObjectNode().put(fake.baseUrl(), "secret-tok");
            agents.insert(agentId, tenantId, "mcp-agent", "you use mcp", "summarize_cell",
                    tools, schema, 5, 60, null, "wt-tok", false, null, null, null, null, null, null,
                    mcpCredentials);
            budgetFixtures.seed(tenantId, agentId);

            stub.script(
                    StubLlmScripts.Turn.toolUses(
                            StubLlmScripts.Turn.toolUse("echo", Map.of("q", "hello"))),
                    StubLlmScripts.Turn.endTurn("{\"x\":\"final\"}"));

            var runId = runner.startRunSync(tenantId, agentId, "manual",
                    mapper.readTree("{}"), null, null, null);
            Run r = runStore.get(runId);
            assertThat(r.status()).isEqualTo("done");
            assertThat(r.output().path("x").asText()).isEqualTo("final");

            var captured = toolCalls.findByRun(runId);
            assertThat(captured).hasSize(1);
            var tc = captured.get(0);
            assertThat(tc.toolName()).isEqualTo("echo");
            assertThat(tc.toolType()).isEqualTo("mcp");
            assertThat(tc.isError()).isFalse();
            assertThat(tc.output().path("q").asText()).isEqualTo("hello");
        } finally {
            fake.close();
        }
    }

    @Test void failingMcpToolTerminatesRunLikeHttp() throws Exception {
        var fake = new FakeMcpServer("secret-tok", 0);
        try {
            var tenantId = UUID.randomUUID();
            var tenantName = "tn-" + tenantId;
            tenants.insert(tenantId, tenantName, "h");
            registerRouting(tenantId);

            var tools = mapper.createArrayNode();
            tools.add(mapper.valueToTree(Map.of(
                    "name", "always_error", "type", "mcp", "description", "always fails",
                    "input_schema", Map.of("type", "object"),
                    "mcp_server_url", fake.baseUrl())));
            var agentId = UUID.randomUUID();
            var mcpCredentials = mapper.createObjectNode().put(fake.baseUrl(), "secret-tok");
            agents.insert(agentId, tenantId, "mcp-failing-agent", "p", "summarize_cell",
                    tools, null, 3, 60, null, "wt", false, null, null, null, null, null, null,
                    mcpCredentials);
            budgetFixtures.seed(tenantId, agentId);

            stub.script(
                    StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("always_error", Map.of())));

            var runId = runner.startRunSync(tenantId, agentId, "manual",
                    mapper.readTree("{}"), null, null, null);
            Run r = runStore.get(runId);
            assertThat(r.status()).isEqualTo("failed");
            assertThat(r.error()).startsWith("tool_error: ");
        } finally {
            fake.close();
        }
    }

    @Test void mixedTurnDispatchesHttpSubagentAndMcpTogether() throws Exception {
        var fake = new FakeMcpServer("secret-tok", 0);
        try {
            var tenantId = UUID.randomUUID();
            var tenantName = "tn-" + tenantId;
            tenants.insert(tenantId, tenantName, "h");
            registerRouting(tenantId);

            stubFor(post(urlEqualTo("/tools/http-echo")).willReturn(okJson("{\"output\":{\"hits\":1}}")));

            var beeSchema = mapper.readTree(
                    "{\"type\":\"object\",\"properties\":{\"finding\":{\"type\":\"string\"}},\"required\":[\"finding\"]}");
            var beeId = UUID.randomUUID();
            agents.insert(beeId, tenantId, "bee", "you are bee", "summarize_cell",
                    mapper.createArrayNode(), beeSchema, 5, 60, "wt", false, null, null, null, null, null, null);
            budgetFixtures.seed(tenantId, beeId);

            var mixedTools = mapper.createArrayNode();
            mixedTools.add(mapper.valueToTree(Map.of(
                    "name", "http-echo", "description", "h", "input_schema", Map.of("type", "object"),
                    "webhook_url", "http://localhost:" + wm.port() + "/tools/http-echo")));
            mixedTools.add(mapper.valueToTree(Map.of(
                    "name", "dispatch_bee", "description", "go bee",
                    "input_schema", Map.of("type", "object"),
                    "type", "subagent", "target_agent", "bee")));
            mixedTools.add(mapper.valueToTree(Map.of(
                    "name", "echo", "type", "mcp", "description", "echoes",
                    "input_schema", Map.of("type", "object"),
                    "mcp_server_url", fake.baseUrl())));

            var queenId = UUID.randomUUID();
            var queenSchema = mapper.readTree(
                    "{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\"}},\"required\":[\"verdict\"]}");
            var mcpCredentials = mapper.createObjectNode().put(fake.baseUrl(), "secret-tok");
            agents.insert(queenId, tenantId, "queen-mixed", "you are queen", "summarize_cell",
                    mixedTools, queenSchema, 5, 60, null, "wt-tok", false, null, null, null, null, null, null,
                    mcpCredentials);
            budgetFixtures.seed(tenantId, queenId);

            stub.scriptForAgent("queen-mixed",
                    StubLlmScripts.Turn.toolUses(
                            StubLlmScripts.Turn.toolUse("http-echo", Map.of("q", "x")),
                            StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of("cell_id", "c1")),
                            StubLlmScripts.Turn.toolUse("echo", Map.of("q", "y"))),
                    StubLlmScripts.Turn.endTurn("{\"verdict\":\"shipped\"}"));
            stub.scriptForAgent("bee",
                    StubLlmScripts.Turn.endTurn("{\"finding\":\"interesting cell\"}"));

            var runId = runner.startRunSync(tenantId, queenId, "manual",
                    mapper.readTree("{}"), null, null, null);
            Run r = runStore.get(runId);
            assertThat(r.status()).isEqualTo("done");
            assertThat(r.output().path("verdict").asText()).isEqualTo("shipped");
            verify(1, postRequestedFor(urlEqualTo("/tools/http-echo")));

            var captured = toolCalls.findByRun(runId);
            assertThat(captured).hasSize(3);
            var byName = captured.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            de.vesterion.vistierie.transcript.RunToolCall::toolName, tc -> tc));
            assertThat(byName.get("http-echo").toolType()).isEqualTo("http");
            assertThat(byName.get("dispatch_bee").toolType()).isEqualTo("subagent");
            assertThat(byName.get("echo").toolType()).isEqualTo("mcp");
            assertThat(byName.get("echo").output().path("q").asText()).isEqualTo("y");
        } finally {
            fake.close();
        }
    }
}
