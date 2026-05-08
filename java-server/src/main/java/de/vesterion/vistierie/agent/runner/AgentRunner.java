package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.pricing.PriceTable;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AgentRunner {

    private final AgentRepository agents;
    private final RunStore runs;
    private final RoutingResolver routing;
    private final ProviderRegistry providers;
    private final PriceTable prices;
    private final LlmCallRecorder recorder;
    private final KillSwitchService kill;
    private final TenantRepository tenants;
    private final ToolUseParser parser;
    private final OutputSchemaValidator schema;
    private final ObjectMapper mapper;

    public AgentRunner(AgentRepository agents, RunStore runs,
                       RoutingResolver routing, ProviderRegistry providers,
                       PriceTable prices, LlmCallRecorder recorder,
                       KillSwitchService kill, TenantRepository tenants,
                       ToolUseParser parser, OutputSchemaValidator schema,
                       ObjectMapper mapper) {
        this.agents = agents;
        this.runs = runs;
        this.routing = routing;
        this.providers = providers;
        this.prices = prices;
        this.recorder = recorder;
        this.kill = kill;
        this.tenants = tenants;
        this.parser = parser;
        this.schema = schema;
        this.mapper = mapper;
    }

    /** Synchronous run starter — used in tests; production path enqueues. */
    public String startRunSync(UUID tenantId, UUID agentId, String trigger,
                               JsonNode payload, String parentRunId,
                               String completionWebhook, String completionWebhookToken) {
        var runId = newUlid();
        var agent = agents.findById(agentId).orElseThrow();
        var snapshot = mapper.valueToTree(Map.of(
                "name", agent.name(),
                "system_prompt", agent.systemPrompt(),
                "model_purpose", agent.modelPurpose(),
                "tools", agent.tools(),
                "output_schema", agent.outputSchema(),
                "max_turns", agent.maxTurns(),
                "max_run_seconds", agent.maxRunSeconds(),
                "webhook_token", agent.webhookToken()
        ));
        runs.create(runId, tenantId, agentId, snapshot, agent.version(),
                parentRunId, trigger, payload, completionWebhook, completionWebhookToken);
        execute(runId);
        return runId;
    }

    public void execute(String runId) {
        runs.markRunning(runId);
        Run run = runs.get(runId);
        var snap = run.agentSnapshot();
        int maxTurns = snap.path("max_turns").asInt(25);
        var tenantName = tenants.findById(run.tenantId()).orElseThrow().name();
        var systemPrompt = snap.path("system_prompt").asText();
        var modelPurpose = snap.path("model_purpose").asText();

        ArrayNode messages = mapper.createArrayNode();
        var firstUser = mapper.createObjectNode()
                .put("role", "user")
                .put("content", run.payload() != null ? run.payload().toString() : "");
        messages.add(firstUser);

        for (int turn = 0; turn < maxTurns; turn++) {
            try { kill.check(run.tenantId()); }
            catch (KillSwitchService.KilledException e) {
                runs.markTerminal(runId, "failed", null, "killed: " + e.reason(), null);
                return;
            }

            runs.persistTurn(runId, messages);

            var decision = routing.resolve(tenantName, modelPurpose, null);
            var providerName = decision.provider();
            var provider = providers.get(providerName);

            List<Map<String, Object>> toolsList = toToolsList(snap.path("tools"));

            var providerReq = new ProviderRequest(decision.model(),
                    1024, null, systemPrompt, toMessagesList(messages),
                    toolsList, null, Map.of("agent_name", snap.path("name").asText()));

            var pRes = provider.complete(providerReq);
            var cost = prices.costMicros(decision.model(), pRes.usage());
            recorder.insert(new LlmCallRecorder.Row(
                    newUlid(), run.tenantId(), modelPurpose, null,
                    providerName, decision.model(), "complete",
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, 0, "ok", null));

            if ("end_turn".equals(pRes.stopReason())) {
                JsonNode output;
                if (snap.has("output_schema") && !snap.get("output_schema").isNull()) {
                    try {
                        output = schema.parseAndValidate(pRes.text(), snap.get("output_schema"));
                    } catch (OutputSchemaValidator.SchemaViolation e) {
                        runs.markTerminal(runId, "failed", null, "output_schema: " + e.getMessage(), null);
                        return;
                    }
                } else {
                    output = mapper.createObjectNode().put("text", pRes.text());
                }
                runs.markTerminal(runId, "done", output, null, summarize(pRes.text()));
                return;
            }

            runs.markTerminal(runId, "failed", null, "tool_use not yet implemented", null);
            return;
        }

        runs.markTerminal(runId, "failed", null, "max_turns_exceeded", null);
    }

    private String summarize(String text) {
        if (text == null) return null;
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    private static String newUlid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toToolsList(JsonNode tools) {
        if (tools == null || !tools.isArray()) return List.of();
        try {
            return mapper.treeToValue(tools, mapper.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMessagesList(JsonNode messages) {
        try {
            return mapper.treeToValue(messages, mapper.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
