package de.vesterion.vistierie.agent.webhooks;

import de.vesterion.vistierie.runs.RunEventRecorder;
import de.vesterion.vistierie.runs.RunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class CompletionWebhookDispatcher {

    private final RunRepository runs;
    private final RunEventRecorder events;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final long baseBackoffMillis;

    public CompletionWebhookDispatcher(RunRepository runs, RunEventRecorder events, ObjectMapper mapper,
                                        @Value("${vistierie.agents.completion-webhook.retry-base-millis:5000}") long baseBackoffMillis) {
        this.runs = runs;
        this.events = events;
        this.mapper = mapper;
        this.http = RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()).build();
        this.baseBackoffMillis = baseBackoffMillis;
    }

    public void fire(String runId) {
        executor.submit(() -> doFire(runId));
    }

    private void doFire(String runId) {
        var run = runs.findById(runId).orElse(null);
        if (run == null || run.completionWebhook() == null) return;

        var body = new HashMap<String, Object>();
        body.put("run_id", run.id());
        body.put("agent_version", run.agentVersion());
        body.put("status", run.status());
        body.put("started_at", run.startedAt() == null ? null : run.startedAt().toString());
        body.put("finished_at", run.finishedAt() == null ? null : run.finishedAt().toString());
        body.put("summary", run.summary());
        body.put("output", run.output());
        body.put("error", run.error());

        long[] delays = { 0L, baseBackoffMillis, baseBackoffMillis * 6L };
        for (int attempt = 0; attempt < delays.length; attempt++) {
            if (delays[attempt] > 0) {
                try { Thread.sleep(delays[attempt]); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            try {
                http.post()
                        .uri(run.completionWebhook())
                        .header("Authorization", "Bearer " + (run.completionWebhookToken() == null ? "" : run.completionWebhookToken()))
                        .header("X-Vistierie-Run-Id", run.id())
                        .header("content-type", "application/json")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                events.record(runId, "info", "webhook_sent",
                        mapper.valueToTree(Map.of("attempt", attempt + 1)));
                return;
            } catch (Exception e) {
                events.record(runId, "warn", "webhook_failed",
                        mapper.valueToTree(Map.of("attempt", attempt + 1, "error", e.getMessage())));
            }
        }
    }
}
