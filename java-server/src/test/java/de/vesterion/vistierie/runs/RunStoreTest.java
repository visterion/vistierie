package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.agent.webhooks.CompletionWebhookDispatcher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunStoreTest {

    private final RunRepository repo = mock(RunRepository.class);
    private final RunEventRecorder events = mock(RunEventRecorder.class);
    private final LongPollService longPoll = mock(LongPollService.class);
    private final CompletionWebhookDispatcher webhook = mock(CompletionWebhookDispatcher.class);
    private final RunStore store = new RunStore(repo, events, longPoll, webhook);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void createDelegatesAsQueued() {
        var tenantId = UUID.randomUUID();
        var agentId = UUID.randomUUID();
        var snap = mapper.createObjectNode();
        var payload = mapper.createObjectNode().put("k", "v");

        store.create("R1", tenantId, agentId, snap, 7, "PARENT", "manual",
                payload, "https://hook", "tok");

        verify(repo).insert(eq("R1"), eq(tenantId), eq(agentId), eq(snap), eq(7),
                eq("PARENT"), eq("manual"), eq("queued"),
                eq(payload), eq("https://hook"), eq("tok"), isNull());
        verifyNoInteractions(events, longPoll, webhook);
    }

    @Test void markRunningPersistsAndEmitsTurnStarted() {
        store.markRunning("R1");
        var io = inOrder(repo, events);
        io.verify(repo).markRunning("R1");
        io.verify(events).record("R1", "info", "turn_started", null);
    }

    @Test void markTerminalDoneFiresWebhookAndTerminalEvent() {
        var output = mapper.createObjectNode().put("answer", "42");
        store.markTerminal("R1", "done", output, null, "summary");

        verify(repo).markTerminal("R1", "done", output, null, "summary");
        verify(events).record("R1", "info", "turn_finished", null);
        verify(longPoll).notifyTerminal("R1");
        verify(webhook).fire("R1");
    }

    @Test void markTerminalFailedEmitsErrorEvent() {
        store.markTerminal("R1", "failed", null, "boom", null);
        verify(events).record(eq("R1"), eq("error"), eq("error"), eq(null));
        verify(webhook).fire("R1");
    }

    @Test void persistTurnDelegatesToRepo() {
        var msgs = mapper.createArrayNode();
        store.persistTurn("R1", msgs);
        verify(repo).appendMessages("R1", msgs);
        verifyNoInteractions(events, longPoll, webhook);
    }

    @Test void getThrowsWhenMissing() {
        when(repo.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> store.get("missing"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test void getReturnsRun() {
        var run = new Run(
                "R1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                mapper.createObjectNode(),
                1,
                null,
                "manual",
                "queued",
                mapper.createObjectNode(),
                mapper.createArrayNode(),
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-16T10:00:00Z"),
                null,
                null,
                null);
        when(repo.findById("R1")).thenReturn(Optional.of(run));
        assertThat(store.get("R1")).isSameAs(run);
    }

    @Test void recordEventDelegates() {
        var payload = mapper.createObjectNode();
        store.recordEvent("R1", "info", "tool_use", payload);
        verify(events).record("R1", "info", "tool_use", payload);
    }
}
