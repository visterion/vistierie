package de.vesterion.vistierie.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BudgetEnforcer {

    private static final Logger log = LoggerFactory.getLogger(BudgetEnforcer.class);

    private final TenantBudgetRepository tenantBudgets;
    private final AgentBudgetRepository agentBudgets;
    private final BudgetUsageRepository usageRepo;
    private final Clock clock;

    // In-process reservation state. Deployment is SINGLE-INSTANCE (no replicas in any compose
    // file), so a per-JVM reservation is a correct and sufficient guard against the check-then-
    // record TOCTOU. A future multi-instance deploy would need a cross-instance (DB-level)
    // reservation instead — revisit this if replicas are ever added.
    private final ConcurrentHashMap<UUID, AtomicLong> tenantInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> agentInFlight = new ConcurrentHashMap<>();
    // Single lock serializing the read-check-reserve critical section so two concurrent callers
    // cannot both pass while summing each other out. Hold time is dominated by two indexed DB
    // SUMs; acceptable for a single-instance gateway whose real latency is the provider call.
    private final Object reserveLock = new Object();

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

    /**
     * Reserves {@code estimatedMicros} of in-flight cost after re-running the same four-way cap
     * check as {@link #checkOrThrow} but with committed usage PLUS current in-flight reservations
     * PLUS the requested estimate. This closes the check-then-record race: concurrent callers see
     * each other's in-flight cost and cannot all pass a cap they would jointly exceed.
     *
     * <p>The returned {@link Reservation} MUST be closed (ideally in a finally / try-with-resources)
     * once the call's real cost has been recorded to {@code llm_calls}; closing releases the
     * reservation so the DB row becomes the sole accounting of that cost (no double-counting).
     */
    public Reservation reserveOrThrow(UUID tenantId, String tenantName, UUID agentId,
                                      String agentName, long estimatedMicros) {
        var tenantCounter = tenantInFlight.computeIfAbsent(tenantId, k -> new AtomicLong());
        var agentCounter = agentInFlight.computeIfAbsent(agentId, k -> new AtomicLong());
        synchronized (reserveLock) {
            BudgetPolicy tenantBudget = tenantBudgets.findByTenantId(tenantId)
                    .filter(BudgetPolicy::operational)
                    .orElseThrow(() -> BudgetException.missingTenant(tenantName, agentName));
            BudgetPolicy agentBudget = agentBudgets.findByAgentId(agentId)
                    .filter(BudgetPolicy::operational)
                    .orElseThrow(() -> BudgetException.missingAgent(tenantName, agentName));

            Instant now = Instant.now(clock);
            var tenantUsage = usageRepo.usageForTenant(tenantId, now);
            var agentUsage = usageRepo.usageForAgent(agentId, now);

            long tenantReserved = tenantCounter.get() + estimatedMicros;
            long agentReserved = agentCounter.get() + estimatedMicros;

            enforceCap("tenant", "daily", tenantBudget.dailyCapMicros(),
                    tenantUsage.dailyMicros() + tenantReserved, tenantName, agentName);
            enforceCap("tenant", "monthly", tenantBudget.monthlyCapMicros(),
                    tenantUsage.monthlyMicros() + tenantReserved, tenantName, agentName);
            enforceCap("agent", "daily", agentBudget.dailyCapMicros(),
                    agentUsage.dailyMicros() + agentReserved, tenantName, agentName);
            enforceCap("agent", "monthly", agentBudget.monthlyCapMicros(),
                    agentUsage.monthlyMicros() + agentReserved, tenantName, agentName);

            // Warn thresholds mirror checkOrThrow: evaluated against committed usage only.
            logWarnIfNeeded("tenant", "daily", tenantBudget.dailyCapMicros(), tenantBudget.dailyWarnPercent(),
                    tenantUsage.dailyMicros(), tenantName, agentName);
            logWarnIfNeeded("tenant", "monthly", tenantBudget.monthlyCapMicros(), tenantBudget.monthlyWarnPercent(),
                    tenantUsage.monthlyMicros(), tenantName, agentName);
            logWarnIfNeeded("agent", "daily", agentBudget.dailyCapMicros(), agentBudget.dailyWarnPercent(),
                    agentUsage.dailyMicros(), tenantName, agentName);
            logWarnIfNeeded("agent", "monthly", agentBudget.monthlyCapMicros(), agentBudget.monthlyWarnPercent(),
                    agentUsage.monthlyMicros(), tenantName, agentName);

            tenantCounter.addAndGet(estimatedMicros);
            agentCounter.addAndGet(estimatedMicros);

            var result = new BudgetCheckResult(
                    remaining(tenantBudget.dailyCapMicros(), tenantUsage.dailyMicros()),
                    remaining(tenantBudget.monthlyCapMicros(), tenantUsage.monthlyMicros()),
                    remaining(agentBudget.dailyCapMicros(), agentUsage.dailyMicros()),
                    remaining(agentBudget.monthlyCapMicros(), agentUsage.monthlyMicros()));
            return new Reservation(tenantCounter, agentCounter, estimatedMicros, result);
        }
    }

    /**
     * Handle for an in-flight cost reservation. {@link #close()} releases the reserved micros back
     * out of the in-flight totals and is idempotent (closing twice does not double-subtract).
     */
    public static final class Reservation implements AutoCloseable {
        private final AtomicLong tenantCounter;
        private final AtomicLong agentCounter;
        private final long micros;
        private final BudgetCheckResult budget;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Reservation(AtomicLong tenantCounter, AtomicLong agentCounter,
                            long micros, BudgetCheckResult budget) {
            this.tenantCounter = tenantCounter;
            this.agentCounter = agentCounter;
            this.micros = micros;
            this.budget = budget;
        }

        public BudgetCheckResult budget() {
            return budget;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                tenantCounter.addAndGet(-micros);
                agentCounter.addAndGet(-micros);
            }
        }
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
