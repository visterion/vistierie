package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.runs.RunStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class AgentDispatcher {

    private final AgentRepository agents;
    private final RunStore runs;
    private final AsyncRunExecutor asyncExecutor;
    private final ObjectMapper mapper;

    public AgentDispatcher(AgentRepository agents, RunStore runs,
                           AsyncRunExecutor asyncExecutor, ObjectMapper mapper) {
        this.agents = agents;
        this.runs = runs;
        this.asyncExecutor = asyncExecutor;
        this.mapper = mapper;
    }

    /**
     * Backward-compatible trigger without sessionId.
     * Synchronously creates the run row and queues async execution. Returns the new run id.
     */
    public String trigger(UUID tenantId, Agent agent, String trigger,
                          JsonNode payload,
                          String completionWebhook, String completionWebhookToken) {
        return trigger(tenantId, agent, trigger, payload,
                completionWebhook, completionWebhookToken, null);
    }

    /**
     * Full trigger with optional sessionId.
     * Synchronously creates the run row and queues async execution. Returns the new run id.
     */
    public String trigger(UUID tenantId, Agent agent, String trigger,
                          JsonNode payload,
                          String completionWebhook, String completionWebhookToken,
                          UUID sessionId) {
        var runId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var snap = mapper.createObjectNode();
        snap.put("name", agent.name());
        snap.put("system_prompt", agent.systemPrompt());
        snap.put("model_purpose", agent.modelPurpose());
        snap.set("tools", agent.tools());
        snap.set("output_schema", agent.outputSchema());
        snap.put("max_turns", agent.maxTurns());
        snap.put("max_run_seconds", agent.maxRunSeconds());
        if (agent.maxTokens() != null) snap.put("max_tokens", agent.maxTokens().intValue());
        snap.put("webhook_token", agent.webhookToken());
        runs.create(runId, tenantId, agent.id(), snap, agent.version(),
                null, trigger, payload, completionWebhook, completionWebhookToken,
                sessionId);
        asyncExecutor.execute(runId);
        return runId;
    }
}
