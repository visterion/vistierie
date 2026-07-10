package de.vesterion.vistierie.budget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic unit tests for the in-process budget reservation layer (finding #3).
 * Uses mocked repositories so no Postgres is required and the concurrency window is
 * exercised directly against {@link BudgetEnforcer#reserveOrThrow}.
 */
class BudgetReservationTest {

    private final TenantBudgetRepository tenantBudgets = mock(TenantBudgetRepository.class);
    private final AgentBudgetRepository agentBudgets = mock(AgentBudgetRepository.class);
    private final BudgetUsageRepository usageRepo = mock(BudgetUsageRepository.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final String tenantName = "tn";
    private final String agentName = "ag";

    private BudgetEnforcer enforcer;

    @BeforeEach
    void setUp() {
        // Tenant is not the limiting scope: very high daily cap.
        when(tenantBudgets.findByTenantId(any()))
                .thenReturn(Optional.of(new BudgetPolicy(1_000_000_000L, null, null, null)));
        // Agent daily cap = 100. One call estimated at 60 fits, two (120) exceed.
        when(agentBudgets.findByAgentId(any()))
                .thenReturn(Optional.of(new BudgetPolicy(100L, null, null, null)));
        // No committed usage yet.
        when(usageRepo.usageForTenant(any(), any()))
                .thenReturn(new BudgetUsageRepository.Usage(0L, 0L));
        when(usageRepo.usageForAgent(any(), any()))
                .thenReturn(new BudgetUsageRepository.Usage(0L, 0L));
        enforcer = new BudgetEnforcer(tenantBudgets, agentBudgets, usageRepo, Clock.systemUTC());
    }

    @Test
    void concurrentBurstExceedingCapDoesNotAllSucceed() throws Exception {
        int threads = 8;
        long estimate = 60L; // 100-cap allows exactly one in-flight reservation of 60
        var pool = Executors.newFixedThreadPool(threads);
        var startGate = new CountDownLatch(1);
        var ready = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        var successes = new AtomicInteger();
        var rejections = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                        // Reservations are held (not closed) so in-flight cost accumulates,
                        // forcing the race window open exactly as concurrent provider calls would.
                        enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, estimate);
                        successes.incrementAndGet();
                    } catch (BudgetException e) {
                        rejections.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            startGate.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The TOCTOU bug would let all 8 pass; the reservation must cap successes.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(threads - 1);
    }

    @Test
    void closingReservationReturnsInFlightToZeroSoSequentialCallsProceed() {
        for (int i = 0; i < 3; i++) {
            try (var res = enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 60L)) {
                assertThat(res.budget()).isNotNull();
            }
        }
        // No leak: a fourth call still succeeds.
        try (var res = enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 60L)) {
            assertThat(res).isNotNull();
        }
    }

    @Test
    void reservationReleasedEvenWhenGuardedBlockThrows() {
        assertThatThrownBy(() -> {
            try (var res = enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 60L)) {
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class);

        // Reservation released despite the exception: next call proceeds.
        try (var res = enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 60L)) {
            assertThat(res).isNotNull();
        }
    }

    @Test
    void doubleCloseDoesNotDoubleSubtract() {
        var r = enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 50L);
        r.close();
        r.close(); // idempotent — must not push in-flight negative

        // In-flight is exactly 0 now: a 50 reservation fits, a second concurrent 50 (=100) exceeds.
        var r2 = enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 50L);
        assertThatThrownBy(() -> enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 50L))
                .isInstanceOf(BudgetException.class);
        r2.close();
    }

    @Test
    void zeroEstimateStillEnforcesCommittedCap() {
        // Committed usage already at the cap: even a zero-cost (subscription) reservation must reject.
        when(usageRepo.usageForAgent(any(), any()))
                .thenReturn(new BudgetUsageRepository.Usage(100L, 0L));
        assertThatThrownBy(() -> enforcer.reserveOrThrow(tenantId, tenantName, agentId, agentName, 0L))
                .isInstanceOf(BudgetException.class)
                .satisfies(e -> assertThat(((BudgetException) e).code()).isEqualTo("budget_exceeded_agent_daily"));
    }
}
