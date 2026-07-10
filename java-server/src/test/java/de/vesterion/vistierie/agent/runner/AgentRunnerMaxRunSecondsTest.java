package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for finding #7: {@code max_run_seconds} must be enforced as a per-turn
 * wall-clock deadline, not merely snapshotted. The injected {@link Clock} advances a fixed
 * step on every read, so a run whose turns keep the loop iterating eventually crosses the
 * deadline and fails with {@code max_run_seconds_exceeded} — while a run comfortably within
 * the limit (frozen clock) is unaffected.
 *
 * <p>Against unenforced code {@link #tripsWhenDeadlineExceededMidLoop()} fails: the run ends
 * {@code done} (the deadline is never checked).
 */
@ActiveProfiles("test-stub-llm")
@Import(AgentRunnerMaxRunSecondsTest.SteppingClockConfig.class)
class AgentRunnerMaxRunSecondsTest extends PostgresTestBase {

    /** A clock that advances {@code STEP_SECONDS} on each read, anchored at {@code BASE}. */
    @TestConfiguration
    static class SteppingClockConfig {
        static final AtomicReference<Instant> BASE = new AtomicReference<>(Instant.now());
        static final AtomicLong STEP_SECONDS = new AtomicLong(0);
        static final AtomicLong READS = new AtomicLong(0);

        static void reset(Instant base, long stepSeconds) {
            BASE.set(base);
            STEP_SECONDS.set(stepSeconds);
            READS.set(0);
        }

        @Bean @Primary Clock steppingClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() {
                    long n = READS.getAndIncrement();
                    return BASE.get().plusSeconds(STEP_SECONDS.get() * n);
                }
            };
        }
    }

    @Autowired AgentRunner runner;
    @Autowired AgentRepository agents;
    @Autowired TenantRepository tenants;
    @Autowired RunStore runStore;
    @Autowired RunRepository runRepo;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    @BeforeEach void resetStub() { stub.resetAll(); }

    private void registerRouting(UUID tId) {
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();
    }

    /** queen (max_run_seconds) with a subagent tool -> bee (unlimited); mirrors real topology. */
    private UUID[] registerQueenAndBee(UUID tenantId, int queenMaxRunSeconds) throws Exception {
        var beeSchema = mapper.readTree(
                "{\"type\":\"object\",\"properties\":{\"finding\":{\"type\":\"string\"}},\"required\":[\"finding\"]}");
        var beeId = UUID.randomUUID();
        // bee has NO wall-clock limit (0) so the shared stepping clock never trips the child.
        agents.insert(beeId, tenantId, "bee", "p", "summarize_cell",
                mapper.createArrayNode(), beeSchema, 5, 0, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, beeId);

        var queenTools = mapper.createArrayNode();
        queenTools.add(mapper.valueToTree(Map.of(
                "name", "dispatch_bee", "description", "go",
                "input_schema", Map.of("type", "object"),
                "type", "subagent", "target_agent", "bee")));
        var queenSchema = mapper.readTree(
                "{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\"}},\"required\":[\"verdict\"]}");
        var queenId = UUID.randomUUID();
        agents.insert(queenId, tenantId, "queen", "p", "summarize_cell",
                queenTools, queenSchema, 25, queenMaxRunSeconds, "wt", false, null, null, null, null, null, null);
        budgetFixtures.seed(tenantId, queenId);
        return new UUID[]{queenId, beeId};
    }

    @Test void tripsWhenDeadlineExceededMidLoop() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRouting(tenantId);
        // The deadline check is the only reader of this clock (BudgetEnforcer uses its own
        // system clock), so it advances once per turn: turn 0 reads base+0 (< 60s deadline,
        // run executes and spawns bee), turn 1 reads base+100 (crosses it -> trips mid-loop).
        SteppingClockConfig.reset(Instant.now(), 100);
        var ids = registerQueenAndBee(tenantId, 60);
        var queenId = ids[0];

        // Only tool-use turns: the only way to end is the deadline (or max_turns=25), so a
        // trip proves wall-clock enforcement rather than a natural end_turn.
        stub.scriptForAgent("queen",
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of())),
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of())));
        stub.scriptForAgent("bee", StubLlmScripts.Turn.endTurn("{\"finding\":\"ok\"}"));

        var runId = runner.startRunSync(tenantId, queenId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run run = runStore.get(runId);
        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.error()).isEqualTo("max_run_seconds_exceeded");
    }

    @Test void withinLimitRunIsUnaffected() throws Exception {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tn-" + tenantId, "h");
        registerRouting(tenantId);
        // Frozen clock (step 0): never advances past the 60s deadline.
        SteppingClockConfig.reset(Instant.now(), 0);
        var ids = registerQueenAndBee(tenantId, 60);
        var queenId = ids[0];

        stub.scriptForAgent("queen",
                StubLlmScripts.Turn.toolUses(StubLlmScripts.Turn.toolUse("dispatch_bee", Map.of())),
                StubLlmScripts.Turn.endTurn("{\"verdict\":\"shipped\"}"));
        stub.scriptForAgent("bee", StubLlmScripts.Turn.endTurn("{\"finding\":\"ok\"}"));

        var runId = runner.startRunSync(tenantId, queenId, "manual",
                mapper.readTree("{}"), null, null, null);

        Run run = runStore.get(runId);
        assertThat(run.status()).isEqualTo("done");
        assertThat(run.output().path("verdict").asText()).isEqualTo("shipped");
        // The multi-turn loop really iterated (subagent spawned) without a false deadline trip.
        List<Run> children = runRepo.findByParent(runId);
        assertThat(children).hasSize(1);
        assertThat(children.get(0).status()).isEqualTo("done");
    }
}
