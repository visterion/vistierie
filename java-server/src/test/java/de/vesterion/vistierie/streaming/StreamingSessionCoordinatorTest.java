package de.vesterion.vistierie.streaming;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.budget.BudgetException;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.tenants.Tenant;
import de.vesterion.vistierie.tenants.TenantRepository;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test-stub-llm")
@Import(StreamingSessionCoordinatorTest.MutableClockConfig.class)
class StreamingSessionCoordinatorTest extends PostgresTestBase {

    @TestConfiguration
    static class MutableClockConfig {
        static final AtomicReference<Instant> NOW =
                new AtomicReference<>(Instant.parse("2026-06-02T09:30:00Z"));

        @Bean @Primary Clock testClock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired StreamingSessionRepository sessionRepo;
    @Autowired AgentRepository agentRepo;
    @Autowired TenantRepository tenantRepo;
    @Autowired JdbcClient jdbc;

    // Use real repos but mock dispatcher/kill/budget for isolation
    AgentDispatcher dispatcher;
    KillSwitchService kill;
    BudgetEnforcer budgets;
    TenantRepository mockTenants;
    StreamingSessionCoordinator coordinator;

    UUID tenantId;
    UUID agentId;
    Agent streamingAgent;

    static final ObjectMapper M = new ObjectMapper();

    @BeforeEach void setUp() throws Exception {
        MutableClockConfig.NOW.set(Instant.parse("2026-06-02T09:30:00Z"));
        dispatcher = mock(AgentDispatcher.class);
        kill = mock(KillSwitchService.class);
        budgets = mock(BudgetEnforcer.class);
        mockTenants = mock(TenantRepository.class);

        var fakeTenant = new Tenant(UUID.randomUUID(), "test-tenant", "h",
                null, null, null, Instant.now());
        when(mockTenants.findById(any())).thenReturn(Optional.of(fakeTenant));
        when(dispatcher.trigger(any(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn("RUNID001");

        var clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return MutableClockConfig.NOW.get(); }
        };

        coordinator = new StreamingSessionCoordinator(
                sessionRepo, mock(EventSourcePoller.class), dispatcher,
                kill, budgets, mockTenants, clock);

        tenantId = UUID.randomUUID();
        tenantRepo.insert(tenantId, "tn-coord-" + tenantId, "h");
        agentId = UUID.randomUUID();
        agentRepo.insert(agentId, tenantId, "daywalker", "system prompt", "summarize_cell",
                M.createArrayNode(), null, 3, 30, "wt", false,
                "0 30 9 * * MON-FRI",
                "https://cb.invalid/done", "cb-token",
                "https://events.invalid/poll", 30600, 60);

        streamingAgent = agentRepo.findById(agentId).orElseThrow();
    }

    @Test
    void openSession_whenCronFiredAndNoOpenSession() {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        MutableClockConfig.NOW.set(now);
        // Use a fresh coordinator with real EventSourcePoller that won't be called
        var realPoller = mock(EventSourcePoller.class);
        when(realPoller.poll(any(), any(), any(), any(), any(), any()))
                .thenReturn(EventSourcePoller.PollResult.ok(List.of()));
        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, true);

        var session = sessionRepo.findOpenByAgent(agentId);
        assertThat(session).isPresent();
        assertThat(session.get().status()).isEqualTo("open");
        assertThat(session.get().closesAt())
                .isEqualTo(now.plusSeconds(30600));
    }

    @Test
    void noOpenSession_whenCronNotFired() {
        var realPoller = mock(EventSourcePoller.class);
        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(Instant.parse("2026-06-02T09:30:00Z"), ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        assertThat(sessionRepo.findOpenByAgent(agentId)).isEmpty();
        verifyNoInteractions(realPoller);
    }

    @Test
    void pollSpawnsRunPerEvent() throws Exception {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var closesAt = now.plusSeconds(30600);
        var sessionId = UUID.randomUUID();
        sessionRepo.insertOpen(sessionId, tenantId, agentId, now, closesAt);

        var event1 = M.readTree("{\"symbol\":\"AAPL\",\"type\":\"spike\"}");
        var event2 = M.readTree("{\"symbol\":\"MSFT\",\"type\":\"spike\"}");

        var realPoller = mock(EventSourcePoller.class);
        when(realPoller.poll(eq("https://events.invalid/poll"), eq("wt"),
                eq(sessionId), eq("daywalker"), isNull(), eq(now)))
                .thenReturn(EventSourcePoller.PollResult.ok(List.of(event1, event2)));

        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verify(dispatcher, times(2)).trigger(
                eq(tenantId), eq(streamingAgent), eq("session_event"),
                any(), eq("https://cb.invalid/done"), eq("cb-token"),
                eq(sessionId));
        assertThat(sessionRepo.findOpenByAgent(agentId).get().lastPollAt())
                .isEqualTo(now);
    }

    @Test
    void pollSkippedWhenPollIntervalNotElapsed() {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var sessionId = UUID.randomUUID();
        sessionRepo.insertOpen(sessionId, tenantId, agentId,
                now.minusSeconds(3600), now.plusSeconds(27000));
        // Set last_poll_at = 30 seconds ago (poll_interval = 60s, so not due yet)
        sessionRepo.updateLastPoll(sessionId, now.minusSeconds(30));

        var realPoller = mock(EventSourcePoller.class);
        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verifyNoInteractions(realPoller);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void sessionClosedWhenWindowExpires() {
        var now = Instant.parse("2026-06-02T18:00:00Z");
        var sessionId = UUID.randomUUID();
        // Session that closed at 17:00
        sessionRepo.insertOpen(sessionId, tenantId, agentId,
                Instant.parse("2026-06-02T09:30:00Z"),
                Instant.parse("2026-06-02T17:00:00Z"));

        var realPoller = mock(EventSourcePoller.class);
        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        var session = sessionRepo.findOpenByAgent(agentId);
        assertThat(session).isEmpty();
        assertThat(sessionRepo.listByAgent(agentId, 10).get(0).status()).isEqualTo("closed");
        verifyNoInteractions(realPoller);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void killSwitchBlocksSpawn() {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var sessionId = UUID.randomUUID();
        sessionRepo.insertOpen(sessionId, tenantId, agentId, now, now.plusSeconds(30600));

        doThrow(new KillSwitchService.KilledException("drill", now.plusSeconds(3600)))
                .when(kill).check(tenantId);

        var realPoller = mock(EventSourcePoller.class);
        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verifyNoInteractions(realPoller);
        verifyNoInteractions(dispatcher);
        // last_poll_at updated despite kill (so we don't spam checks)
        assertThat(sessionRepo.findOpenByAgent(agentId).get().lastPollAt()).isEqualTo(now);
    }

    @Test
    void budgetExhausted_skipsEvent() throws Exception {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var sessionId = UUID.randomUUID();
        sessionRepo.insertOpen(sessionId, tenantId, agentId, now, now.plusSeconds(30600));

        var event = M.readTree("{\"symbol\":\"AAPL\"}");
        var realPoller = mock(EventSourcePoller.class);
        when(realPoller.poll(any(), any(), any(), any(), any(), any()))
                .thenReturn(EventSourcePoller.PollResult.ok(List.of(event)));
        doThrow(BudgetException.exceeded("tenant", "daily", "test-tenant", "daywalker"))
                .when(budgets).checkOrThrow(any(), any(), any(), any());

        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verifyNoInteractions(dispatcher);
    }

    @Test
    void emptyEvents_noRunsSpawned() {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var sessionId = UUID.randomUUID();
        sessionRepo.insertOpen(sessionId, tenantId, agentId, now, now.plusSeconds(30600));

        var realPoller = mock(EventSourcePoller.class);
        when(realPoller.poll(any(), any(), any(), any(), any(), any()))
                .thenReturn(EventSourcePoller.PollResult.ok(List.of()));

        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verifyNoInteractions(dispatcher);
        assertThat(sessionRepo.findOpenByAgent(agentId).get().lastPollAt()).isEqualTo(now);
    }

    @Test
    void failedPoll_doesNotAdvanceCursor_soNextTickRetriesSameWindow() {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var sessionId = UUID.randomUUID();
        var priorLastPoll = now.minusSeconds(120);
        sessionRepo.insertOpen(sessionId, tenantId, agentId,
                now.minusSeconds(3600), now.plusSeconds(27000));
        sessionRepo.updateLastPoll(sessionId, priorLastPoll);

        var realPoller = mock(EventSourcePoller.class);
        when(realPoller.poll(any(), any(), any(), any(), any(), any()))
                .thenReturn(EventSourcePoller.PollResult.failed());

        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verifyNoInteractions(dispatcher);
        assertThat(sessionRepo.findOpenByAgent(agentId).get().lastPollAt())
                .as("cursor must be preserved so the next tick retries the same since-window")
                .isEqualTo(priorLastPoll);
    }

    @Test
    void successfulEmptyPoll_advancesCursor() {
        var now = Instant.parse("2026-06-02T09:30:00Z");
        var sessionId = UUID.randomUUID();
        var priorLastPoll = now.minusSeconds(120);
        sessionRepo.insertOpen(sessionId, tenantId, agentId,
                now.minusSeconds(3600), now.plusSeconds(27000));
        sessionRepo.updateLastPoll(sessionId, priorLastPoll);

        var realPoller = mock(EventSourcePoller.class);
        when(realPoller.poll(any(), any(), any(), any(), any(), any()))
                .thenReturn(EventSourcePoller.PollResult.ok(List.of()));

        var c = new StreamingSessionCoordinator(sessionRepo, realPoller, dispatcher,
                kill, budgets, mockTenants,
                Clock.fixed(now, ZoneOffset.UTC));

        c.handleTick(streamingAgent, false);

        verifyNoInteractions(dispatcher);
        assertThat(sessionRepo.findOpenByAgent(agentId).get().lastPollAt())
                .isEqualTo(now);
    }
}
