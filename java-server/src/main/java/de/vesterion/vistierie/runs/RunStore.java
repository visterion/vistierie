package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.agent.webhooks.CompletionWebhookDispatcher;
import de.vesterion.vistierie.transcript.RunSearchIndexer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Component
public class RunStore {

    private final RunRepository repo;
    private final RunEventRecorder events;
    private final LongPollService longPoll;
    private final CompletionWebhookDispatcher webhook;
    private final RunSearchIndexer searchIndexer;

    public RunStore(RunRepository repo, RunEventRecorder events, LongPollService longPoll,
                    CompletionWebhookDispatcher webhook, RunSearchIndexer searchIndexer) {
        this.repo = repo;
        this.events = events;
        this.longPoll = longPoll;
        this.webhook = webhook;
        this.searchIndexer = searchIndexer;
    }

    /** Backward-compatible overload without sessionId. */
    public void create(String runId, UUID tenantId, UUID agentId,
                       JsonNode agentSnapshot, int agentVersion,
                       String parentRunId, String trigger,
                       JsonNode payload, String completionWebhook,
                       String completionWebhookToken) {
        create(runId, tenantId, agentId, agentSnapshot, agentVersion,
                parentRunId, trigger, payload, completionWebhook,
                completionWebhookToken, null);
    }

    /** Full create with optional sessionId. */
    public void create(String runId, UUID tenantId, UUID agentId,
                       JsonNode agentSnapshot, int agentVersion,
                       String parentRunId, String trigger,
                       JsonNode payload, String completionWebhook,
                       String completionWebhookToken, UUID sessionId) {
        repo.insert(runId, tenantId, agentId, agentSnapshot, agentVersion,
                parentRunId, trigger, "queued", payload,
                completionWebhook, completionWebhookToken, sessionId);
    }

    public void markRunning(String runId) {
        repo.markRunning(runId);
        events.record(runId, "info", "turn_started", null);
    }

    public void persistTurn(String runId, JsonNode messages) {
        repo.appendMessages(runId, messages);
    }

    public void recordEvent(String runId, String level, String type, JsonNode payload) {
        events.record(runId, level, type, payload);
    }

    public void markTerminal(String runId, String status, JsonNode output, String error, String summary) {
        repo.markTerminal(runId, status, output, error, summary);
        events.record(runId, status.equals("done") ? "info" : "error",
                status.equals("done") ? "turn_finished" : "error", null);
        longPoll.notifyTerminal(runId);
        webhook.fire(runId);
        searchIndexer.index(runId);
    }

    public Run get(String runId) {
        return repo.findById(runId).orElseThrow();
    }
}
