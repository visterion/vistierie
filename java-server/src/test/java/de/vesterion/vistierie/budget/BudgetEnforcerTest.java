package de.vesterion.vistierie.budget;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetEnforcerTest extends PostgresTestBase {

    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired TenantBudgetRepository tenantBudgets;
    @Autowired AgentBudgetRepository agentBudgets;
    @Autowired BudgetUsageRepository usageRepo;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    UUID tenantId;
    UUID agentId;
    String tenantName;
    String agentName;
    Clock clock;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantName = "tenant-" + tenantId.toString().substring(0, 8);
        tenants.insert(tenantId, tenantName, "tok");
        agentId = UUID.randomUUID();
        agentName = "agent-" + agentId.toString().substring(0, 8);
        agents.insert(agentId, tenantId, agentName, "sys", "routine",
                mapper.createArrayNode(), null, 5, 60, "wt", false, null);
        clock = Clock.fixed(Instant.parse("2026-05-16T10:15:30Z"), ZoneOffset.UTC);
    }

    @Test
    void exceededAgentDailyBudgetBlocksEvenWhenTenantBudgetRemains() {
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(10_000L, null, null, null));
        agentBudgets.patch(agentId, new BudgetPatchRequest(500L, null, null, null));
        insertCall(tenantId, agentId, 600L, Instant.now(clock));

        var enforcer = new BudgetEnforcer(tenantBudgets, agentBudgets, usageRepo, clock);

        assertThatThrownBy(() -> enforcer.checkOrThrow(tenantId, tenantName, agentId, agentName))
                .isInstanceOf(BudgetException.class)
                .hasMessageContaining("agent daily")
                .satisfies(error -> {
                    var ex = (BudgetException) error;
                    assertThat(ex.code()).isEqualTo("budget_exceeded_agent_daily");
                    assertThat(ex.tenant()).isEqualTo(tenantName);
                    assertThat(ex.agentName()).isEqualTo(agentName);
                    assertThat(ex.period()).isEqualTo("daily");
                    assertThat(ex.remainingMicros()).isEqualTo(0L);
                });
    }

    @Test
    void missingAgentBudgetBlocksEvenWhenTenantBudgetExists() {
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(5_000L, 50_000L, null, null));

        var enforcer = new BudgetEnforcer(tenantBudgets, agentBudgets, usageRepo, clock);

        assertThatThrownBy(() -> enforcer.checkOrThrow(tenantId, tenantName, agentId, agentName))
                .isInstanceOf(BudgetException.class)
                .hasMessageContaining("agent budget")
                .satisfies(error -> assertThat(((BudgetException) error).code()).isEqualTo("budget_missing_agent"));
    }

    @Test
    void returnsRemainingBudgetAcrossTenantAndAgentPeriodsWhenWithinLimits() {
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(1_000L, 5_000L, 50, 75));
        agentBudgets.patch(agentId, new BudgetPatchRequest(800L, 2_000L, 50, 75));
        insertCall(tenantId, agentId, 300L, Instant.parse("2026-05-16T08:00:00Z"));
        insertCall(tenantId, agentId, 200L, Instant.parse("2026-05-10T08:00:00Z"));

        var enforcer = new BudgetEnforcer(tenantBudgets, agentBudgets, usageRepo, clock);

        var result = enforcer.checkOrThrow(tenantId, tenantName, agentId, agentName);

        assertThat(result.tenantDailyRemaining()).isEqualTo(700L);
        assertThat(result.tenantMonthlyRemaining()).isEqualTo(4_500L);
        assertThat(result.agentDailyRemaining()).isEqualTo(500L);
        assertThat(result.agentMonthlyRemaining()).isEqualTo(1_500L);
    }

    private void insertCall(UUID tenantId, UUID agentId, long costMicros, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO vistierie.llm_calls
                  (id, tenant_id, agent_id, purpose, provider, model, endpoint, status, cost_micros, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(UUID.randomUUID().toString(), tenantId, agentId, "routine", "anthropic", "haiku",
                        "complete", "ok", costMicros, Timestamp.from(createdAt))
                .update();
    }
}
