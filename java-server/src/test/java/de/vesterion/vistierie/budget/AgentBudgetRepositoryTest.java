package de.vesterion.vistierie.budget;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBudgetRepositoryTest extends PostgresTestBase {

    @Autowired TenantRepository tenants;
    @Autowired AgentRepository agents;
    @Autowired AgentBudgetRepository repo;
    @Autowired ObjectMapper mapper;

    UUID agentId;

    @BeforeEach
    void seed() {
        var tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tenant-" + tenantId.toString().substring(0, 8), "tok");
        agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "agent-" + agentId.toString().substring(0, 8),
                "sys", "routine", mapper.createArrayNode(), null, 5, 60, "wt", false, null, null, null);
    }

    @Test
    void patchCanSetAndClearAgentBudgetFieldsIndependently() {
        repo.patch(agentId, new BudgetPatchRequest(2_000L, 8_000L, 70, 85));
        repo.patch(agentId, new BudgetPatchRequest(3_000L, null, 75, null));

        BudgetPolicy stored = repo.findByAgentId(agentId).orElseThrow();

        assertThat(stored.dailyCapMicros()).isEqualTo(3_000L);
        assertThat(stored.monthlyCapMicros()).isNull();
        assertThat(stored.dailyWarnPercent()).isEqualTo(75);
        assertThat(stored.monthlyWarnPercent()).isNull();
        assertThat(stored.operational()).isTrue();
    }
}
