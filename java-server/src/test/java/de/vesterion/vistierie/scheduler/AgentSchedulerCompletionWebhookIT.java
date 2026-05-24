package de.vesterion.vistierie.scheduler;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@ActiveProfiles("test-stub-llm")
@TestPropertySource(properties = "vistierie.agents.scheduler.tick-millis=200")
class AgentSchedulerCompletionWebhookIT extends PostgresTestBase {

    static WireMockServer wm;

    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired JdbcClient jdbc;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    @BeforeAll
    static void startWm() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopWm() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void resetWm() {
        wm.resetAll();
        wm.stubFor(post(urlPathEqualTo("/cb"))
                .willReturn(aResponse().withStatus(204)));
    }

    @Test
    void cronTriggeredRunFiresCompletionWebhook() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-cwit-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");

        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();

        var script = new ArrayList<StubLlmScripts.ScriptedTurn>();
        for (int i = 0; i < 20; i++) script.add(StubLlmScripts.Turn.endTurn("{\"x\":\"v" + i + "\"}"));
        stub.script(script.toArray(new StubLlmScripts.ScriptedTurn[0]));

        var schema = mapper.readTree(
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        var callbackUrl = wm.baseUrl() + "/cb";
        agents.insert(agentId, tenantId, "cw-test-agent", "test prompt", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt-cwit", false,
                "* * * * * *",
                callbackUrl, "cb-token-xyz");
        budgetFixtures.seed(tenantId, agentId);

        // Wait for the webhook to be called with the correct auth header and run-id
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        wm.verify(postRequestedFor(urlPathEqualTo("/cb"))
                                .withHeader("Authorization", equalTo("Bearer cb-token-xyz"))
                                .withHeader("X-Vistierie-Run-Id", matching(".+"))));
    }
}
