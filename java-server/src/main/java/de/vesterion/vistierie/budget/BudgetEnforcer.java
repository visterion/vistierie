package de.vesterion.vistierie.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class BudgetEnforcer {

    private static final Logger log = LoggerFactory.getLogger(BudgetEnforcer.class);

    private final TenantBudgetRepository tenantBudgets;
    private final AgentBudgetRepository agentBudgets;
    private final BudgetUsageRepository usageRepo;
    private final Clock clock;

    @Autowired
    public BudgetEnforcer(TenantBudgetRepository tenantBudgets,
                          AgentBudgetRepository agentBudgets,
                          BudgetUsageRepository usageRepo) {
        this(tenantBudgets, agentBudgets, usageRepo, Clock.systemUTC());
    }

    BudgetEnforcer(TenantBudgetRepository tenantBudgets,
                   AgentBudgetRepository agentBudgets,
                   BudgetUsageRepository usageRepo,
                   Clock clock) {
        this.tenantBudgets = tenantBudgets;
        this.agentBudgets = agentBudgets;
        this.usageRepo = usageRepo;
        this.clock = clock;
    }

    public record BudgetCheckResult(
            Long tenantDailyRemaining,
            Long tenantMonthlyRemaining,
            Long agentDailyRemaining,
            Long agentMonthlyRemaining
    ) {}

    public BudgetCheckResult checkOrThrow(UUID tenantId, String tenantName, UUID agentId, String agentName) {
        BudgetPolicy tenantBudget = tenantBudgets.findByTenantId(tenantId)
                .filter(BudgetPolicy::operational)
                .orElseThrow(() -> BudgetException.missingTenant(tenantName, agentName));
        BudgetPolicy agentBudget = agentBudgets.findByAgentId(agentId)
                .filter(BudgetPolicy::operational)
                .orElseThrow(() -> BudgetException.missingAgent(tenantName, agentName));

        Instant now = Instant.now(clock);
        var tenantUsage = usageRepo.usageForTenant(tenantId, now);
        var agentUsage = usageRepo.usageForAgent(agentId, now);

        enforceCap("tenant", "daily", tenantBudget.dailyCapMicros(), tenantUsage.dailyMicros(), tenantName, agentName);
        enforceCap("tenant", "monthly", tenantBudget.monthlyCapMicros(), tenantUsage.monthlyMicros(), tenantName, agentName);
        enforceCap("agent", "daily", agentBudget.dailyCapMicros(), agentUsage.dailyMicros(), tenantName, agentName);
        enforceCap("agent", "monthly", agentBudget.monthlyCapMicros(), agentUsage.monthlyMicros(), tenantName, agentName);

        logWarnIfNeeded("tenant", "daily", tenantBudget.dailyCapMicros(), tenantBudget.dailyWarnPercent(),
                tenantUsage.dailyMicros(), tenantName, agentName);
        logWarnIfNeeded("tenant", "monthly", tenantBudget.monthlyCapMicros(), tenantBudget.monthlyWarnPercent(),
                tenantUsage.monthlyMicros(), tenantName, agentName);
        logWarnIfNeeded("agent", "daily", agentBudget.dailyCapMicros(), agentBudget.dailyWarnPercent(),
                agentUsage.dailyMicros(), tenantName, agentName);
        logWarnIfNeeded("agent", "monthly", agentBudget.monthlyCapMicros(), agentBudget.monthlyWarnPercent(),
                agentUsage.monthlyMicros(), tenantName, agentName);

        return new BudgetCheckResult(
                remaining(tenantBudget.dailyCapMicros(), tenantUsage.dailyMicros()),
                remaining(tenantBudget.monthlyCapMicros(), tenantUsage.monthlyMicros()),
                remaining(agentBudget.dailyCapMicros(), agentUsage.dailyMicros()),
                remaining(agentBudget.monthlyCapMicros(), agentUsage.monthlyMicros())
        );
    }

    private static void enforceCap(String scope, String period, Long capMicros, long usageMicros,
                                   String tenantName, String agentName) {
        if (capMicros != null && usageMicros >= capMicros) {
            throw BudgetException.exceeded(scope, period, tenantName, agentName);
        }
    }

    private void logWarnIfNeeded(String scope, String period, Long capMicros, Integer warnPercent,
                                 long usageMicros, String tenantName, String agentName) {
        if (capMicros == null || warnPercent == null) return;
        if (usageMicros * 100L >= capMicros * warnPercent) {
            log.warn("budget warning scope={} tenant={} agent={} period={} usedMicros={} capMicros={}",
                    scope, tenantName, agentName, period, usageMicros, capMicros);
        }
    }

    private static Long remaining(Long capMicros, long usageMicros) {
        if (capMicros == null) return null;
        long remaining = capMicros - usageMicros;
        return Math.max(0L, remaining);
    }
}
