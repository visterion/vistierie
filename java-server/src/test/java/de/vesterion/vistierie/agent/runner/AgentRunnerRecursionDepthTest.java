package de.vesterion.vistierie.agent.runner;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for finding #1: the subagent recursion-depth limit must fire across the
 * async subagent boundary. Each subagent runs on a fresh virtual thread; a ThreadLocal
 * depth counter resets to 0 there, so the limit never engages and an A->B->A cycle
 * recurses unbounded (stopped only if a budget cap happens to trip).
 *
 * <p>{@code max-depth} is pinned to 2 for this context. The A->B->A scripts give the cycle
 * a finite ceiling of 4 runs, so DEPTH — not any budget/script accident — is what must cut
 * it to 3: a working guard blocks the depth-3 dispatch. Against the old ThreadLocal code
 * the guard never engages (each subagent runs on a fresh virtual thread where the counter
 * is 0), so the depth-2 run spawns a 4th run and the run completes without a depth error.
 */
@ActiveProfiles("test-stub-llm")
@TestPropertySource(properties = "vistierie.agents.subagent.max-depth=2")
class AgentRunnerRecursionDepthTest extends PostgresTestBase {

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;
    @Autowired JdbcClient jdbc;

    @BeforeEach void resetStub() { stub.resetAll(); }

    private void registerRouting(UUID tId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    private void insertCyclicAgent(UUID id, UUID tenantId, String name, String targetName) {
        var tools = mapper.createArrayNode();
        tools.add(mapper.valueToTree(Map.of(
                "name", "dispatch_" + targetName, "description", "go",
                "input_schema", Map.of("type", "object"),
                "type", "subagent", "target_agent", targetName)));
        agents.insert(id, tenantId, name, "p", "summarize_cell",
                tools, null, 5, 60, "wt", false, null, null, null, null, null, null);
    }

    @Test void depthLimitFiresAcrossAsyncSubagentBoundary() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        registerRouting(tenantId);

        var queenId = UUID.randomUUID();
        var beeId = UUID.randomUUID();
        // A -> B -> A cycle (repository insert bypasses the self-reference validator).
        insertCyclicAgent(queenId, tenantId, "queen", "bee");
        insertCyclicAgent(beeId, tenantId, "bee", "queen");
        budgetFixtures.seed(tenantId, queenId);
        budgetFixtures.seed(tenantId, beeId);

        // queen is invoked twice in the cycle (root + at depth 2); each invocation dispatches bee.
        stub.scriptForAgent("queen",
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of())),
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of())));
        // bee is invoked once (at depth 1); it dispatches queen.
        stub.scriptForAgent("bee",
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_queen", Map.of())));

        var rootRunId = runner.startRunSync(tenantId, queenId, "manual",
                mapper.readTree("{}"), null, null, null);

        long totalRuns = jdbc.sql("SELECT count(*) FROM vistierie.runs WHERE tenant_id = ?")
                .param(tenantId).query(Long.class).single();
        // depth 0 (root queen) -> 1 (bee) -> 2 (queen'); queen' dispatching at childDepth 3 is
        // blocked. Exactly three runs, and no unbounded recursion.
        assertThat(totalRuns).isEqualTo(3L);

        Run root = runStore.get(rootRunId);
        assertThat(root.status()).isEqualTo("failed");
        // The depth breach surfaces as a tool error and propagates up as the run error.
        assertThat(root.error()).contains("subagent depth exceeded");
    }
}
