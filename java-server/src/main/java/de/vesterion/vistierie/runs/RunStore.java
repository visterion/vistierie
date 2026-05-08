package de.vesterion.vistierie.runs;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Component
public class RunStore {

    private final RunRepository repo;
    private final RunEventRecorder events;

    public RunStore(RunRepository repo, RunEventRecorder events) {
        this.repo = repo; this.events = events;
    }

    public void create(String runId, UUID tenantId, UUID agentId,
                       JsonNode agentSnapshot, int agentVersion,
                       String parentRunId, String trigger,
                       JsonNode payload, String completionWebhook, String completionWebhookToken) {
        repo.insert(runId, tenantId, agentId, agentSnapshot, agentVersion,
                parentRunId, trigger, "queued", payload,
                completionWebhook, completionWebhookToken);
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
    }

    public Run get(String runId) {
        return repo.findById(runId).orElseThrow();
    }
}
