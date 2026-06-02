package de.vesterion.vistierie.agent.webhooks;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@TestPropertySource(properties = "vistierie.agents.completion-webhook.retry-base-millis=200")
class CompletionWebhookDispatcherTest extends PostgresTestBase {

    static WireMockServer wm;

    @Autowired RunStore runStore;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper mapper;

    @BeforeEach void up() {
        if (wm == null) { wm = new WireMockServer(0); wm.start(); }
        configureFor("localhost", wm.port());
        wm.resetAll();
    }
    @AfterEach void resetWm() { wm.resetAll(); }

    @Test void firesOnTerminalAndRetries() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                JsonNodeFactory.instance.arrayNode(), null, 3, 30, "wt", false, null, null, null, null, null, null);
        var runId = "01J" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
        var snap = mapper.readTree("{\"version\":1,\"name\":\"a\"}");
        runStore.create(runId, tenantId, agentId, snap, 1, null, "manual",
                mapper.createObjectNode(),
                "http://localhost:" + wm.port() + "/done", "wt-token");

        stubFor(post(urlEqualTo("/done")).inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        stubFor(post(urlEqualTo("/done")).inScenario("flaky")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)));

        runStore.markTerminal(runId, "done", mapper.readTree("{\"text\":\"ok\"}"), null, "summary");

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(2, postRequestedFor(urlEqualTo("/done"))
                        .withHeader("Authorization", equalTo("Bearer wt-token"))
                        .withHeader("X-Vistierie-Run-Id", equalTo(runId))));
    }
}
