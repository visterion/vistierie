package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the register/read race in {@link RunController#get}.
 * See java-server task-6 brief: RunStore.markTerminal() writes terminal state
 * THEN calls longPoll.notifyTerminal(id), which drains+removes the waiter list.
 * If that happens in the gap between the controller's initial findById and its
 * longPoll.register call, a naively-ordered controller would register a wakeup
 * that is never drained, and the request would hang until wait_seconds timeout.
 */
class RunControllerTest {

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentDispatcher dispatcher = mock(AgentDispatcher.class);
    private final BudgetEnforcer budgets = mock(BudgetEnforcer.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final RunEventRecorder events = mock(RunEventRecorder.class);
    private final LongPollService longPoll = new LongPollService();

    private final RunController controller =
            new RunController(agents, dispatcher, budgets, runs, events, longPoll);

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach void setTenant() {
        RequestContext.set(new RequestContext.Principal(tenantId, "tn", false));
    }

    @AfterEach void clearTenant() {
        RequestContext.clear();
    }

    private Run run(String id, String status) {
        return new Run(id, tenantId, UUID.randomUUID(), null, 1, null, "manual", status,
                null, null, null, null, null, null, null,
                Instant.now(), status.equals("done") || status.equals("failed") ? Instant.now() : null,
                null, null);
    }

    /**
     * Simulates the terminal transition happening in the register gap:
     * the controller's initial findById(id) sees "running", but by the time it
     * re-queries after registering the waiter, the run has already gone terminal
     * (and notifyTerminal has already drained an empty waiter list).
     * The fix must resolve the DeferredResult immediately rather than waiting
     * for onTimeout.
     */
    @Test void resolvesImmediatelyWhenRunTurnsTerminalInRegisterGap() {
        var id = "run-race";
        var running = run(id, "running");
        var terminal = run(id, "done");
        when(runs.findById(id)).thenReturn(Optional.of(running), Optional.of(terminal));
        when(agents.findById(running.agentId())).thenReturn(Optional.empty());
        when(runs.findByParent(id)).thenReturn(List.of());

        var deferred = controller.get(id, 30);

        assertThat(deferred.hasResult())
                .as("deferred should resolve immediately when the run is already terminal by the time the waiter is registered")
                .isTrue();
        var result = (org.springframework.http.ResponseEntity<?>) deferred.getResult();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Normal wakeup path: run is running at both reads; notifyTerminal wakes the deferred later. */
    @Test void resolvesViaWakeupWhenNotifiedAfterRegistration() {
        var id = "run-wakeup";
        var running = run(id, "running");
        var terminal = run(id, "done");
        when(runs.findById(id)).thenReturn(Optional.of(running), Optional.of(running), Optional.of(terminal));
        when(agents.findById(running.agentId())).thenReturn(Optional.empty());
        when(runs.findByParent(id)).thenReturn(List.of());

        var deferred = controller.get(id, 30);

        assertThat(deferred.hasResult()).isFalse();

        longPoll.notifyTerminal(id);

        await().atMost(java.time.Duration.ofSeconds(5)).until(deferred::hasResult);
        var result = (org.springframework.http.ResponseEntity<?>) deferred.getResult();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void immediateTerminalReturnsResultWithoutRegistering() {
        var id = "run-done";
        var terminal = run(id, "done");
        when(runs.findById(id)).thenReturn(Optional.of(terminal));
        when(agents.findById(terminal.agentId())).thenReturn(Optional.empty());
        when(runs.findByParent(id)).thenReturn(List.of());

        var deferred = controller.get(id, 30);

        assertThat(deferred.hasResult()).isTrue();
        var result = (org.springframework.http.ResponseEntity<?>) deferred.getResult();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void noWaitReturnsCurrentStateImmediately() {
        var id = "run-nowait";
        var running = run(id, "running");
        when(runs.findById(id)).thenReturn(Optional.of(running));
        when(agents.findById(running.agentId())).thenReturn(Optional.empty());
        when(runs.findByParent(id)).thenReturn(List.of());

        var deferred = controller.get(id, 0);

        assertThat(deferred.hasResult()).isTrue();
        var result = (org.springframework.http.ResponseEntity<?>) deferred.getResult();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void notFoundReturns404() {
        var id = "run-missing";
        when(runs.findById(id)).thenReturn(Optional.empty());

        var deferred = controller.get(id, 30);

        assertThat(deferred.hasResult()).isTrue();
        var result = (org.springframework.http.ResponseEntity<?>) deferred.getResult();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void wrongTenantReturns404() {
        var id = "run-other-tenant";
        var otherTenantRun = new Run(id, UUID.randomUUID(), UUID.randomUUID(), null, 1, null, "manual", "running",
                null, null, null, null, null, null, null, Instant.now(), null, null, null);
        when(runs.findById(id)).thenReturn(Optional.of(otherTenantRun));

        var deferred = controller.get(id, 30);

        assertThat(deferred.hasResult()).isTrue();
        var result = (org.springframework.http.ResponseEntity<?>) deferred.getResult();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
