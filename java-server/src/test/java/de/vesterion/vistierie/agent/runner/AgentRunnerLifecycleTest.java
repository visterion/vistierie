package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
class AgentRunnerLifecycleTest extends PostgresTestBase {

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;

    @BeforeEach void resetStub() { stub.resetAll(); }

    private record Ids(UUID tenantId, UUID agentId) {}

    /** Inserts a tenant + catch-all routing + a no-schema agent (purpose summarize_cell) with budgets. */
    private Ids newAgent() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "a", "you are a", "summarize_cell",
                JsonNodeFactory.instance.arrayNode(), null, 5, 60, "wt",
                false, null, null, null, null, null, null);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        return new Ids(tenantId, agentId);
    }

    @Test void providerExceptionMarksRunFailedNotRunning() throws Exception {
        var ids = newAgent();
        stub.failNextComplete(new RuntimeException("bedrock boom"));

        var runId = runner.startRunSync(ids.tenantId(), ids.agentId(), "manual",
                mapper.readTree("{\"q\":\"hi\"}"), null, null, null);

        Run r = runStore.get(runId);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).contains("internal_error").contains("bedrock boom");
    }
}
