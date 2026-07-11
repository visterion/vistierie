package de.vesterion.vistierie.agent.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.provider.ClaudeSubscriptionProvider;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent runs routed to {@code claude-subscription} are billed as free (cost 0, shadow cost logged)
 * and thread the bridge session id between turns via the {@code provider_session_id} metadata key.
 */
@ActiveProfiles("test-stub-llm")
class AgentRunnerSubscriptionBillingTest extends PostgresTestBase {

    /** Registers the stub under the {@code claude-subscription} name, optionally injecting a session id. */
    @TestConfiguration
    static class SubscriptionConfig {
        /** When non-null, the NEXT complete() response carries this session id, then clears. */
        static final AtomicReference<String> NEXT_SESSION = new AtomicReference<>();

        @Bean
        LlmProvider subscriptionDelegate(StubLlmProvider stub) {
            return new LlmProvider() {
                @Override public String name() { return ClaudeSubscriptionProvider.NAME; }
                @Override public ProviderResponse complete(ProviderRequest req) {
                    var r = stub.complete(req);
                    var sid = NEXT_SESSION.getAndSet(null);
                    if (sid == null) return r;
                    return new ProviderResponse(r.text(), r.stopReason(), r.usage(),
                            r.model(), r.contentBlocks(), sid);
                }
                @Override public ProviderResponse vision(String m, int t, String mt, String b, String p) {
                    throw new UnsupportedOperationException("n/a");
                }
            };
        }
    }

    static WireMockServer wm;

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    @BeforeEach void up() {
        if (wm == null) { wm = new WireMockServer(0); wm.start(); }
        configureFor("localhost", wm.port());
        wm.resetAll();
        stub.resetAll();
        SubscriptionConfig.NEXT_SESSION.set(null);
    }

    @AfterEach void down() { wm.resetAll(); }

    /** Routes {@code summarize_cell} to the subscription provider (priced model, but billed free). */
    private void registerSubscriptionRouting(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                ClaudeSubscriptionProvider.NAME, "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    @Test void subscriptionRunBilledFree() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerSubscriptionRouting(tenantId);
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        stub.script(StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        var row = jdbc.sql("SELECT provider, cost_micros, shadow_cost_micros FROM vistierie.llm_calls WHERE run_id = ?")
                .param(runId).query().singleRow();
        assertThat(row).containsEntry("provider", ClaudeSubscriptionProvider.NAME);
        assertThat(((Number) row.get("cost_micros")).longValue()).isZero();
        // Subscription calls log a shadow cost (what the API-key path would have cost).
        assertThat(row.get("shadow_cost_micros")).isNotNull();
    }

    @Test void threadsSessionIdAcrossTurns() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerSubscriptionRouting(tenantId);

        stubFor(post(urlEqualTo("/tools/probe")).willReturn(okJson("{\"output\":{\"ok\":true}}")));
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", "probe", "description", "p", "input_schema", Map.of("type", "object"),
                "webhook_url", "http://localhost:" + wm.port() + "/tools/probe")));
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                tools, null, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);

        // Turn 1: tool_use (carries session id s-1). Turn 2: end_turn (no session id).
        SubscriptionConfig.NEXT_SESSION.set("s-1");
        stub.script(
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("probe", Map.of("q", "x"))),
                StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        // The second (final) provider request must carry the session id captured from turn 1.
        var lastMeta = stub.lastRequest().metadata();
        assertThat(lastMeta).containsEntry("provider_session_id", "s-1");
    }
}
