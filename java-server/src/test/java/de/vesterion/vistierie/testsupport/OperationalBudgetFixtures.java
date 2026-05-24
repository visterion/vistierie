package de.vesterion.vistierie.testsupport;

import de.vesterion.vistierie.budget.AgentBudgetRepository;
import de.vesterion.vistierie.budget.TenantBudgetRepository;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OperationalBudgetFixtures {

    private static final long TENANT_DAILY_CAP_MICROS = 1_000_000_000L;
    private static final long TENANT_MONTHLY_CAP_MICROS = 10_000_000_000L;
    private static final long AGENT_DAILY_CAP_MICROS = 500_000_000L;
    private static final long AGENT_MONTHLY_CAP_MICROS = 5_000_000_000L;

    private final TenantBudgetRepository tenantBudgets;
    private final AgentBudgetRepository agentBudgets;

    public OperationalBudgetFixtures(TenantBudgetRepository tenantBudgets,
                                     AgentBudgetRepository agentBudgets) {
        this.tenantBudgets = tenantBudgets;
        this.agentBudgets = agentBudgets;
    }

    public void seed(UUID tenantId, UUID agentId) {
        tenantBudgets.patch(tenantId, new BudgetPatchRequest(
                TENANT_DAILY_CAP_MICROS, TENANT_MONTHLY_CAP_MICROS, 80, 90));
        agentBudgets.patch(agentId, new BudgetPatchRequest(
                AGENT_DAILY_CAP_MICROS, AGENT_MONTHLY_CAP_MICROS, 80, 90));
    }
}
