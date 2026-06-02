package de.vesterion.vistierie.budget;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetUsageRepositoryTest extends PostgresTestBase {

    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired BudgetUsageRepository repo;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    UUID tenantId;
    UUID agentId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tenant-" + tenantId.toString().substring(0, 8), "tok");
        agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "agent-" + agentId.toString().substring(0, 8),
                "sys", "routine", mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
    }

    @Test
    void usageForTenantSumsCurrentUtcDayAndMonth() {
        var now = Instant.parse("2026-05-16T10:15:30Z");

        insertCall(tenantId, agentId, 100L, Instant.parse("2026-05-16T00:00:00Z"));
        insertCall(tenantId, agentId, 200L, Instant.parse("2026-05-16T09:00:00Z"));
        insertCall(tenantId, agentId, 300L, Instant.parse("2026-05-10T12:00:00Z"));
        insertCall(tenantId, agentId, 400L, Instant.parse("2026-04-30T23:59:59Z"));

        var usage = repo.usageForTenant(tenantId, now);

        assertThat(usage.dailyMicros()).isEqualTo(300L);
        assertThat(usage.monthlyMicros()).isEqualTo(600L);
    }

    @Test
    void usageForAgentIgnoresOtherAgentsAndPreviousMonth() {
        var otherAgentId = UUID.randomUUID();
        agents.insert(otherAgentId, tenantId, "agent-" + otherAgentId.toString().substring(0, 8),
                "sys", "routine", mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null, null, null, null);
        var now = Instant.parse("2026-05-16T10:15:30Z");

        insertCall(tenantId, agentId, 500L, Instant.parse("2026-05-16T08:00:00Z"));
        insertCall(tenantId, agentId, 600L, Instant.parse("2026-05-03T08:00:00Z"));
        insertCall(tenantId, otherAgentId, 700L, Instant.parse("2026-05-16T08:30:00Z"));
        insertCall(tenantId, agentId, 800L, Instant.parse("2026-04-10T08:00:00Z"));

        var usage = repo.usageForAgent(agentId, now);

        assertThat(usage.dailyMicros()).isEqualTo(500L);
        assertThat(usage.monthlyMicros()).isEqualTo(1_100L);
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
