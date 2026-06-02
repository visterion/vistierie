package de.vesterion.vistierie.scheduler;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
@Import(AgentSchedulerTest.MutableClockConfig.class)
class AgentSchedulerTest extends PostgresTestBase {

    @TestConfiguration
    static class MutableClockConfig {
        static final AtomicReference<Instant> NOW =
                new AtomicReference<>(Instant.parse("2026-05-08T00:00:30Z"));
        @Bean @Primary Clock testClock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired AgentScheduler scheduler;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired JdbcClient jdbc;

    UUID tenantId;
    UUID agentId;

    @BeforeEach void up() {
        stub.resetAll();
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:00:30Z"));
        tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();

        agentId = UUID.randomUUID();
        var schema = readObjectSchema();
        agents.insert(agentId, tenantId, "a", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null,
                null, null, null, null, null);
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, 100_000L, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(5_000L, 50_000L, 80, 90));
        // Set schedule and backdate created_at so the cron boundary falls within the test window
        jdbc.sql("UPDATE vistierie.agents SET schedule='0 * * * * *', created_at='2026-05-08T00:00:00Z' WHERE id=?")
                .param(agentId).update();
    }

    private tools.jackson.databind.JsonNode readObjectSchema() {
        try { return mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void tickFiresAtCronBoundaryAndUpdatesLastTick() {
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));

        // 00:00:30 — before next boundary (00:01:00). No run.
        scheduler.tick();
        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();

        // Advance to 00:01:00 — boundary reached.
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:00Z"));
        scheduler.tick();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(runs.findByTenant(tenantId, 10))
                        .hasSize(1)
                        .allSatisfy(r -> assertThat(r.trigger()).isEqualTo("cron")));
        assertThat(agents.findById(agentId).orElseThrow().lastTickAt()).isNotNull();

        // Advance 30s within the same minute — no second run.
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:30Z"));
        scheduler.tick();
        assertThat(runs.findByTenant(tenantId, 10)).hasSize(1);
    }

    @Test
    void skipIfRunningRecordsCronSkippedAndDoesNotFireSecondRun() {
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));

        // First fire at 00:01:00
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:00Z"));
        scheduler.tick();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(runs.findByTenant(tenantId, 10)).hasSize(1));

        // Force the run back to 'running' so the second tick sees it as open.
        var firstRunId = runs.findByTenant(tenantId, 10).get(0).id();
        jdbc.sql("UPDATE vistierie.runs SET status='running', finished_at=NULL WHERE id=?")
                .param(firstRunId).update();

        // Advance to next cron boundary 00:02:00, tick.
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:02:00Z"));
        scheduler.tick();

        // No second run.
        assertThat(runs.findByTenant(tenantId, 10)).hasSize(1);

        // cron_skipped event recorded on the open run.
        var events = jdbc.sql(
                "SELECT type FROM vistierie.run_events WHERE run_id=? AND type='cron_skipped'")
                .param(firstRunId).query(String.class).list();
        assertThat(events).containsExactly("cron_skipped");

        // last_tick_at advanced regardless.
        assertThat(agents.findById(agentId).orElseThrow().lastTickAt())
                .isAfterOrEqualTo(Instant.parse("2026-05-08T00:02:00Z"));
    }

    @Test
    void killedTenantBlocksCronFire() {
        stub.script(StubLlmScripts.Turn.endTurn("{\"x\":\"v\"}"));

        // Kill tenant for one hour.
        tenants.setKill(tenantId,
                Instant.parse("2026-05-08T01:00:00Z"), "drill", "operator");

        // Boundary reached.
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:00Z"));
        scheduler.tick();

        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();
        // last_tick_at still advances so we don't replay missed boundaries on unkill.
        assertThat(agents.findById(agentId).orElseThrow().lastTickAt()).isNotNull();
    }

    @Test
    void schedulerSkipsAgentWithoutOperationalBudget() {
        jdbc.sql("DELETE FROM vistierie.agent_budgets WHERE agent_id = ?").param(agentId).update();
        MutableClockConfig.NOW.set(Instant.parse("2026-05-08T00:01:00Z"));

        scheduler.tick();

        assertThat(runs.findByTenant(tenantId, 10)).isEmpty();
    }
}
