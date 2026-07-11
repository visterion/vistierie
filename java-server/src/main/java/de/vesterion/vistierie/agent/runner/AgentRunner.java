package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.agents.dto.ToolDef;
import de.vesterion.vistierie.audit.LlmCallRecorder;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.budget.BudgetException;
import de.vesterion.vistierie.kill.KillSwitchService;
import de.vesterion.vistierie.pricing.PriceTable;
import de.vesterion.vistierie.provider.ClaudeSubscriptionProvider;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunStore;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.transcript.RunToolCall;
import de.vesterion.vistierie.transcript.RunToolCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** Per-turn output-token cap applied when an agent does not set its own {@code max_tokens}. */
    static final int DEFAULT_MAX_TOKENS = 8192;

    private final AgentRepository agents;
    private final RunStore runs;
    private final RoutingResolver routing;
    private final ProviderRegistry providers;
    private final PriceTable prices;
    private final LlmCallRecorder recorder;
    private final BudgetEnforcer budgets;
    private final KillSwitchService kill;
    private final TenantRepository tenants;
    private final ToolUseParser parser;
    private final OutputSchemaValidator schema;
    private final ToolDispatcher toolDispatcher;
    private final RecursionGuard recursionGuard;
    private final ExecutorService subagentExecutor;
    private final ObjectMapper mapper;
    private final RunToolCallRepository toolCalls;
    private final Clock clock;

    public AgentRunner(AgentRepository agents, RunStore runs,
                       RoutingResolver routing, ProviderRegistry providers,
                       PriceTable prices, LlmCallRecorder recorder,
                       BudgetEnforcer budgets,
                       KillSwitchService kill, TenantRepository tenants,
                       ToolUseParser parser, OutputSchemaValidator schema,
                       ToolDispatcher toolDispatcher,
                       RecursionGuard recursionGuard,
                       ExecutorService subagentExecutor,
                       ObjectMapper mapper,
                       RunToolCallRepository toolCalls,
                       Clock clock) {
        this.agents = agents;
        this.runs = runs;
        this.routing = routing;
        this.providers = providers;
        this.prices = prices;
        this.recorder = recorder;
        this.budgets = budgets;
        this.kill = kill;
        this.tenants = tenants;
        this.parser = parser;
        this.schema = schema;
        this.toolDispatcher = toolDispatcher;
        this.recursionGuard = recursionGuard;
        this.subagentExecutor = subagentExecutor;
        this.mapper = mapper;
        this.toolCalls = toolCalls;
        this.clock = clock;
    }

    /** Synchronous run starter — used in tests; production path enqueues. */
    public String startRunSync(UUID tenantId, UUID agentId, String trigger,
                               JsonNode payload, String parentRunId,
                               String completionWebhook, String completionWebhookToken) {
        return startRunSync(tenantId, agentId, trigger, payload, parentRunId,
                completionWebhook, completionWebhookToken, 0);
    }

    /**
     * Depth-aware variant used by the subagent spawn path. {@code depth} is the recursion
     * depth of the run being started (0 for a top-level run) and is propagated into the
     * turn loop so the subagent depth limit survives the async spawn boundary.
     */
    private String startRunSync(UUID tenantId, UUID agentId, String trigger,
                                JsonNode payload, String parentRunId,
                                String completionWebhook, String completionWebhookToken,
                                int depth) {
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
        if (agent.maxTokens() != null) snap.put("max_tokens", agent.maxTokens().intValue());
        snap.put("webhook_token", agent.webhookToken());
        snap.set("mcp_credentials", agent.mcpCredentials() != null
                ? agent.mcpCredentials() : mapper.createObjectNode());
        JsonNode snapshot = snap;
        runs.create(runId, tenantId, agentId, snapshot, agent.version(),
                parentRunId, trigger, payload, completionWebhook, completionWebhookToken);
        execute(runId, depth);
        return runId;
    }

    public void execute(String runId) {
        execute(runId, 0);
    }

    private void execute(String runId, int depth) {
        try {
            executeInternal(runId, depth);
        } catch (Exception e) {
            log.warn("run {} aborted with uncaught exception: {}", runId, e.toString());
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            runs.markTerminal(runId, "failed", null, "internal_error: " + detail, null);
        }
    }

    private void executeInternal(String runId, int depth) {
        runs.markRunning(runId);
        Run run = runs.get(runId);
        var snap = run.agentSnapshot();
        int maxTurns = snap.path("max_turns").asInt(25);
        int maxTokens = snap.path("max_tokens").asInt(DEFAULT_MAX_TOKENS);
        // Wall-clock budget. <= 0 (absent/zero) means no limit. The deadline is anchored to
        // the run's start instant so slow tools/providers cannot exceed the configured wall
        // clock even within a single turn.
        long maxRunSeconds = snap.path("max_run_seconds").asLong(0);
        Instant deadline = null;
        if (maxRunSeconds > 0) {
            Instant startedAt = run.startedAt() != null ? run.startedAt() : clock.instant();
            deadline = startedAt.plusSeconds(maxRunSeconds);
        }
        var tenantName = tenants.findById(run.tenantId()).orElseThrow().name();
        var systemPrompt = snap.path("system_prompt").asText();
        var modelPurpose = snap.path("model_purpose").asText();

        ArrayNode messages = mapper.createArrayNode();
        // Bedrock (and other providers) reject a blank first content block, so fall back to a
        // non-empty kickoff when the run carries no payload (e.g. scheduled/cron runs).
        String firstContent = run.payload() != null ? run.payload().toString() : "";
        if (firstContent.isBlank()) firstContent = "Begin the run.";
        var firstUser = mapper.createObjectNode()
                .put("role", "user")
                .put("content", firstContent);
        messages.add(firstUser);

        // Provider-session thread: the subscription bridge returns a session id on tool_use turns
        // so the next turn can resume the same SDK session (see ClaudeSubscriptionProvider). Null
        // until the first such response; reset to null whenever a turn is served by the fallback
        // provider (bridge sessions are provider-specific and must not leak across providers).
        String providerSessionId = null;

        for (int turn = 0; turn < maxTurns; turn++) {
            try { kill.check(run.tenantId()); }
            catch (KillSwitchService.KilledException e) {
                runs.markTerminal(runId, "failed", null, "killed: " + e.reason(), null);
                return;
            }
            if (deadline != null && clock.instant().isAfter(deadline)) {
                runs.markTerminal(runId, "failed", null, "max_run_seconds_exceeded", null);
                return;
            }

            runs.persistTurn(runId, messages);

            var decision = routing.resolve(tenantName, null, modelPurpose, null);
            var providerName = decision.provider();
            var provider = providers.get(providerName);

            List<Map<String, Object>> toolsList = toToolsList(snap.path("tools"));

            String agentName = snap.path("name").asText();
            // Carry the threaded session id (when present) so the subscription bridge resumes the
            // same SDK session on the next turn.
            Map<String, Object> metadata = providerSessionId == null
                    ? Map.of("agent_name", agentName)
                    : Map.of("agent_name", agentName, "provider_session_id", providerSessionId);
            var providerReq = new ProviderRequest(decision.model(),
                    maxTokens, null, systemPrompt, toMessagesList(messages),
                    toolsList, null, metadata);

            // Reserve estimated cost across this turn's provider call + audit write so concurrent
            // runs (e.g. parallel subagents) see this turn's in-flight cost in their own cap check.
            // Released in the finally regardless of outcome; the real cost lands in the DB row.
            String callId = newUlid();
            long estimate = estimateMicros(providerName, decision.model(), maxTokens);
            ProviderResponse pRes;
            String usedProvider = providerName;
            String usedModel = decision.model();
            var usedReq = providerReq;
            try (var res = budgets.reserveOrThrow(run.tenantId(), tenantName,
                    run.agentId(), agentName, estimate)) {
                boolean fellBack = false;
                try {
                    pRes = provider.complete(providerReq);
                } catch (RuntimeException e) {
                    // Mirror LlmService: on a retryable primary failure with a configured fallback,
                    // retry once against the fallback provider. Never send the bridge session id
                    // cross-provider — sessions are bridge-specific.
                    if (decision.fallbackProvider() == null || !shouldFallback(e)) throw e;
                    log.warn("run {} provider {} failed ({}), falling back to {}",
                            runId, providerName, e.toString(), decision.fallbackProvider());
                    runs.recordEvent(runId, "info", "provider_fallback", mapper.valueToTree(
                            Map.of("from", providerName, "to", decision.fallbackProvider())));
                    usedProvider = decision.fallbackProvider();
                    usedModel = decision.fallbackModel();
                    usedReq = new ProviderRequest(usedModel, maxTokens, null, systemPrompt,
                            toMessagesList(messages), toolsList, null,
                            Map.of("agent_name", agentName));
                    pRes = providers.get(usedProvider).complete(usedReq);
                    fellBack = true;
                }
                // Subscription calls are free: real cost 0, the equivalent API-key cost logged as a
                // shadow figure. Other providers bill the real per-turn cost.
                boolean subscription = ClaudeSubscriptionProvider.NAME.equals(usedProvider);
                long cost = subscription ? 0L : prices.costMicros(usedModel, pRes.usage());
                Long shadow = subscription ? shadowCost(usedModel, pRes.usage()) : null;
                recorder.insertWithBody(new LlmCallRecorder.Row(
                        callId, run.tenantId(), run.agentId(), modelPurpose, null,
                        usedProvider, usedModel, "complete",
                        pRes.usage().inputTokens(), pRes.usage().outputTokens(),
                        pRes.usage().cacheCreationInputTokens(), pRes.usage().cacheReadInputTokens(),
                        cost, shadow, 0, "ok", null, runId, null), usedReq, pRes);
                // Session threading: adopt a fresh id whenever the provider returns one; otherwise
                // clear a stale id after a fallback so the next turn doesn't resend it to a provider
                // that never issued it. (end_turn responses return null, which is fine — the run ends.)
                if (pRes.sessionId() != null) providerSessionId = pRes.sessionId();
                else if (fellBack) providerSessionId = null;
            } catch (BudgetException e) {
                runs.markTerminal(runId, "failed", null, e.code() + ": " + e.getMessage(), null);
                return;
            }

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
            if (blocks.isEmpty()) {
                // Reached only when stop_reason != end_turn (end_turn returns above) yet the turn
                // produced no tool_use block — e.g. max_tokens truncated mid-text. Appending an
                // empty tool_results message would make the next provider call fail with
                // "content field empty". Fail cleanly instead.
                runs.markTerminal(runId, "failed", null,
                        "no_tool_use: stop_reason=" + pRes.stopReason(), null);
                return;
            }
            runs.recordEvent(runId, "info", "tool_dispatched",
                    mapper.valueToTree(Map.of("count", blocks.size())));

            Map<String, ToolUseParser.Block> blockById = new HashMap<>();
            for (var bk : blocks) blockById.put(bk.id(), bk);

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
                    final int childDepth = depth + 1;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            recursionGuard.check(childDepth);
                            var childRunId = startRunSync(parentTenantId, targetId, "subagent",
                                    blockRef.input(), parentRunId, null, null, childDepth);
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
                if (td.isMcpTool()) {
                    String mcpToken = snap.path("mcp_credentials").path(td.mcp_server_url()).asText(null);
                    futures.add(toolDispatcher.dispatchMcp(td, b, runId, mcpToken));
                } else {
                    futures.add(toolDispatcher.dispatchHttp(td, b, runId, snap.path("webhook_token").asText()));
                }
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

                try {
                    var blk = blockById.get(res.toolUseId());
                    var bdef = blk == null ? null : toolDefByName.get(blk.name());
                    String toolType = bdef == null ? "unknown"
                            : ("subagent".equals(bdef.path("type").asText()) ? "subagent"
                               : ("mcp".equals(bdef.path("type").asText()) ? "mcp" : "http"));
                    toolCalls.insert(new RunToolCall(
                            newUlid(), runId, run.tenantId(), callId, turn,
                            res.toolUseId(),
                            blk == null ? "?" : blk.name(),
                            toolType,
                            blk == null ? null : blk.input(),
                            res.content(),
                            res.isError(),
                            res.isError() ? res.content().path("error").asText(null) : null,
                            null));
                } catch (Exception e) {
                    log.warn("tool-call transcript capture failed for run {} tool_use {}: {}", runId, res.toolUseId(), e.toString());
                }
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

    /**
     * Conservative pre-call cost estimate for the budget reservation: treat {@code maxTokens} as
     * output tokens (input tokens unknown pre-call). Mirrors this runner's actual billing, which
     * prices every turn via {@link PriceTable#costMicros}; an unpriced model reserves 0.
     */
    private long estimateMicros(String providerName, String model, int maxTokens) {
        // Subscription calls are free — reserve nothing (mirrors LlmService.estimateMicros).
        if (ClaudeSubscriptionProvider.NAME.equals(providerName)) return 0L;
        try {
            return prices.costMicros(model, new de.vesterion.vistierie.pricing.Usage(0, maxTokens, 0, 0));
        } catch (PriceTable.UnknownModelException e) {
            return 0L;
        }
    }

    /** Retryable-primary predicate for provider fallback (mirrors {@code LlmService.shouldFallback}). */
    private static boolean shouldFallback(RuntimeException e) {
        if (e instanceof UnsupportedOperationException) return true;
        if (e instanceof LlmProvider.ProviderException pe) {
            return pe.statusCode() == 429 || pe.statusCode() >= 500;
        }
        return false;
    }

    /** What the API-key path would have cost for a free subscription call; null for unpriced models. */
    private Long shadowCost(String model, de.vesterion.vistierie.pricing.Usage usage) {
        try {
            return prices.costMicros(model, usage);
        } catch (PriceTable.UnknownModelException e) {
            return null;
        }
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
