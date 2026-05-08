package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.runs.RunStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class AgentDispatcher {

    private final AgentRepository agents;
    private final RunStore runs;
    private final AgentRunner runner;
    private final ObjectMapper mapper;

    public AgentDispatcher(AgentRepository agents, RunStore runs,
                           AgentRunner runner, ObjectMapper mapper) {
        this.agents = agents;
        this.runs = runs;
        this.runner = runner;
        this.mapper = mapper;
    }

    /** Synchronously creates the run row and queues async execution. Returns the new run id. */
    public String trigger(UUID tenantId, Agent agent, String trigger,
                          JsonNode payload,
                          String completionWebhook, String completionWebhookToken) {
        var runId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var snap = mapper.createObjectNode();
        snap.put("name", agent.name());
        snap.put("system_prompt", agent.systemPrompt());
        snap.put("model_purpose", agent.modelPurpose());
        snap.set("tools", agent.tools());
        snap.set("output_schema", agent.outputSchema());
        snap.put("max_turns", agent.maxTurns());
        snap.put("max_run_seconds", agent.maxRunSeconds());
        snap.put("webhook_token", agent.webhookToken());
        runs.create(runId, tenantId, agent.id(), snap, agent.version(),
                null, trigger, payload, completionWebhook, completionWebhookToken);
        executeAsync(runId);
        return runId;
    }

    @Async("agentExecutor")
    public void executeAsync(String runId) {
        runner.execute(runId);
    }
}
