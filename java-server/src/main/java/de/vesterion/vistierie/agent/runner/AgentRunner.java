package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.agents.dto.ToolDef;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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
    private final ToolDispatcher toolDispatcher;
    private final RecursionGuard recursionGuard;
    private final ExecutorService subagentExecutor;
    private final ObjectMapper mapper;

    public AgentRunner(AgentRepository agents, RunStore runs,
                       RoutingResolver routing, ProviderRegistry providers,
                       PriceTable prices, LlmCallRecorder recorder,
                       KillSwitchService kill, TenantRepository tenants,
                       ToolUseParser parser, OutputSchemaValidator schema,
                       ToolDispatcher toolDispatcher,
                       RecursionGuard recursionGuard,
                       ExecutorService subagentExecutor,
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
        this.toolDispatcher = toolDispatcher;
        this.recursionGuard = recursionGuard;
        this.subagentExecutor = subagentExecutor;
        this.mapper = mapper;
    }

    /** Synchronous run starter — used in tests; production path enqueues. */
    public String startRunSync(UUID tenantId, UUID agentId, String trigger,
                               JsonNode payload, String parentRunId,
                               String completionWebhook, String completionWebhookToken) {
        var runId = newUlid();
        var agent = agents.findById(agentId).orElseThrow();
        var snap = mapper.createObjectNode();
        snap.put("name", agent.name());
        snap.put("system_prompt", agent.systemPrompt());
        snap.put("model_purpose", agent.modelPurpose());
        snap.set("tools", agent.tools());
        snap.set("output_schema", agent.outputSchema());
        snap.put("max_turns", agent.maxTurns());
        snap.put("max_run_seconds", agent.maxRunSeconds());
        snap.put("webhook_token", agent.webhookToken());
        JsonNode snapshot = snap;
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

            var decision = routing.resolve(tenantName, null, modelPurpose, null);
            var providerName = decision.provider();
            var provider = providers.get(providerName);

            List<Map<String, Object>> toolsList = toToolsList(snap.path("tools"));

            var providerReq = new ProviderRequest(decision.model(),
                    1024, null, systemPrompt, toMessagesList(messages),
                    toolsList, null, Map.of("agent_name", snap.path("name").asText()));

            var pRes = provider.complete(providerReq);
            var cost = prices.costMicros(decision.model(), pRes.usage());
            recorder.insertWithBody(new LlmCallRecorder.Row(
                    newUlid(), run.tenantId(), run.agentId(), modelPurpose, null,
                    providerName, decision.model(), "complete",
                    pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                    pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                    cost, 0, "ok", null, runId, null), providerReq, pRes);

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

            // tool_use turn
            List<ToolUseParser.Block> blocks = parser.parse(pRes.contentBlocks());
            runs.recordEvent(runId, "info", "tool_dispatched",
                    mapper.valueToTree(Map.of("count", blocks.size())));

            Map<String, JsonNode> toolDefByName = new HashMap<>();
            for (JsonNode t : snap.path("tools")) toolDefByName.put(t.path("name").asText(), t);

            var futures = new ArrayList<CompletableFuture<ToolResult>>();
            for (var b : blocks) {
                var def = toolDefByName.get(b.name());
                if (def == null) {
                    var err = new ToolResult(b.id(), true,
                            mapper.createObjectNode().put("error", "unknown tool: " + b.name()));
                    var f = new CompletableFuture<ToolResult>();
                    f.complete(err);
                    futures.add(f);
                    continue;
                }
                if ("subagent".equals(def.path("type").asText())) {
                    var targetName = def.path("target_agent").asText();
                    var target = agents.findByName(run.tenantId(), targetName).orElse(null);
                    if (target == null) {
                        var err = new ToolResult(b.id(), true,
                                mapper.createObjectNode().put("error", "unknown target_agent: " + targetName));
                        var f = new CompletableFuture<ToolResult>();
                        f.complete(err);
                        futures.add(f);
                        continue;
                    }
                    final var targetId = target.id();
                    final var parentTenantId = run.tenantId();
                    final var parentRunId = runId;
                    final var blockRef = b;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            recursionGuard.enter();
                            var childRunId = startRunSync(parentTenantId, targetId, "subagent",
                                    blockRef.input(), parentRunId, null, null);
                            runs.recordEvent(parentRunId, "info", "subagent_spawned",
                                    mapper.valueToTree(Map.of("child_run_id", childRunId, "agent", targetName)));
                            var child = runs.get(childRunId);
                            if ("done".equals(child.status())) {
                                runs.recordEvent(parentRunId, "info", "subagent_finished",
                                        mapper.valueToTree(Map.of("child_run_id", childRunId, "status", "done")));
                                return new ToolResult(blockRef.id(), false, child.output());
                            }
                            runs.recordEvent(parentRunId, "error", "subagent_finished",
                                    mapper.valueToTree(Map.of("child_run_id", childRunId, "status", child.status())));
                            return new ToolResult(blockRef.id(), true,
                                    mapper.createObjectNode().put("error",
                                            "subagent_failed: " + (child.error() == null ? "unknown" : child.error())));
                        } catch (RecursionGuard.DepthExceeded de) {
                            return new ToolResult(blockRef.id(), true,
                                    mapper.createObjectNode().put("error", de.getMessage()));
                        } finally {
                            try { recursionGuard.exit(); } catch (Exception ignored) {}
                        }
                    }, subagentExecutor));
                    continue;
                }
                ToolDef td;
                try {
                    td = mapper.treeToValue(def, ToolDef.class);
                } catch (Exception e) {
                    runs.markTerminal(runId, "failed", null, "bad_tool_def: " + e.getMessage(), null);
                    return;
                }
                futures.add(toolDispatcher.dispatchHttp(td, b, runId, snap.path("webhook_token").asText()));
            }

            var assistantMsg = mapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.set("content", pRes.contentBlocks());
            messages.add(assistantMsg);

            boolean anyError = false;
            String firstError = null;
            var resultsArr = mapper.createArrayNode();
            for (var f : futures) {
                var res = f.join();
                var resBlock = mapper.createObjectNode();
                resBlock.put("type", "tool_result");
                resBlock.put("tool_use_id", res.toolUseId());
                resBlock.set("content", res.content());
                if (res.isError()) {
                    resBlock.put("is_error", true);
                    if (!anyError) { anyError = true; firstError = res.content().path("error").asText(); }
                }
                resultsArr.add(resBlock);
                runs.recordEvent(runId, res.isError() ? "error" : "info",
                        res.isError() ? "tool_failed" : "tool_returned",
                        mapper.valueToTree(Map.of("tool_use_id", res.toolUseId())));
            }

            if (anyError) {
                runs.markTerminal(runId, "failed", null, "tool_error: " + firstError, null);
                return;
            }

            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.set("content", resultsArr);
            messages.add(userMsg);
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
