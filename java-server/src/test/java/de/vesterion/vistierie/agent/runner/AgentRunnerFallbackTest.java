package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent runs must survive a failing primary provider by falling back to the routing rule's
 * configured fallback provider (mirrors {@code LlmService} fallback semantics).
 */
@ActiveProfiles("test-stub-llm")
class AgentRunnerFallbackTest extends PostgresTestBase {

    @TestConfiguration
    static class FailingPrimaryConfig {
        static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

        @Bean
        LlmProvider failingPrimary() {
            return new LlmProvider() {
                @Override public String name() { return "failing"; }
                @Override public ProviderResponse complete(ProviderRequest req) {
                    var e = FAIL.get();
                    if (e != null) throw e;
                    throw new IllegalStateException("no failure scripted");
                }
                @Override public ProviderResponse vision(String m, int t, String mt, String b, String p) {
                    throw new UnsupportedOperationException("n/a");
                }
            };
        }
    }

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

    @BeforeEach void reset() {
        stub.resetAll();
        FailingPrimaryConfig.FAIL.set(null);
    }

    /** Routing rule: primary {@code failing} with a configured fallback to the stub {@code anthropic}. */
    private void registerRoutingWithFallback(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "failing", "x", "anthropic", "claude-haiku-4-5",
                500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    /** Routing rule: primary {@code failing} with NO fallback. */
    private void registerRoutingNoFallback(UUID tenantId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "failing", "x", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    private UUID newAgent(UUID tenantId) {
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, agentId);
        return agentId;
    }

    private String providerOf(String runId) {
        return (String) jdbc.sql("SELECT provider FROM vistierie.llm_calls WHERE run_id = ?")
                .param(runId).query().singleRow().get("provider");
    }

    @Test void unsupportedOperationFallsBackToStub() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingWithFallback(tenantId);
        var agentId = newAgent(tenantId);
        FailingPrimaryConfig.FAIL.set(new UnsupportedOperationException("no tools"));
        stub.script(StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(providerOf(runId)).isEqualTo("anthropic");
    }

    @Test void serverErrorFallsBack() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingWithFallback(tenantId);
        var agentId = newAgent(tenantId);
        FailingPrimaryConfig.FAIL.set(new LlmProvider.ProviderException(500, "server_error", "boom"));
        stub.script(StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("done");
        assertThat(providerOf(runId)).isEqualTo("anthropic");
    }

    @Test void noFallbackConfiguredStillFails() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRoutingNoFallback(tenantId);
        var agentId = newAgent(tenantId);
        FailingPrimaryConfig.FAIL.set(new LlmProvider.ProviderException(500, "server_error", "boom"));
        stub.script(StubLlmScripts.Turn.endTurn("done"));

        var runId = runner.startRunSync(tenantId, agentId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).startsWith("internal_error:");
    }
}
