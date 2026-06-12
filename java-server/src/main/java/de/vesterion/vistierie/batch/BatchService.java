package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.agents.Agent;
import de.vesterion.vistierie.budget.BudgetEnforcer;
import de.vesterion.vistierie.budget.BudgetException;
import de.vesterion.vistierie.provider.BatchItem;
import de.vesterion.vistierie.provider.BatchSubmission;
import de.vesterion.vistierie.provider.ProviderRegistry;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.runs.RunStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BatchService {

    private static final Pattern CUSTOM_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final RunStore runs;
    private final RunRepository runRepo;
    private final RoutingResolver routing;
    private final ProviderRegistry providers;
    private final ObjectMapper mapper;
    private final de.vesterion.vistierie.agent.runner.OutputSchemaValidator schemaValidator;
    private final de.vesterion.vistierie.audit.LlmCallRecorder recorder;
    private final de.vesterion.vistierie.pricing.PriceTable prices;
    private final BudgetEnforcer budgets;
    private final int maxItems;

    public BatchService(RunStore runs, RunRepository runRepo, RoutingResolver routing,
                        ProviderRegistry providers, ObjectMapper mapper,
                        de.vesterion.vistierie.agent.runner.OutputSchemaValidator schemaValidator,
                        de.vesterion.vistierie.audit.LlmCallRecorder recorder,
                        de.vesterion.vistierie.pricing.PriceTable prices,
                        BudgetEnforcer budgets,
                        @Value("${vistierie.agents.batch.max-items:10000}") int maxItems) {
        this.runs = runs;
        this.runRepo = runRepo;
        this.routing = routing;
        this.providers = providers;
        this.mapper = mapper;
        this.schemaValidator = schemaValidator;
        this.recorder = recorder;
        this.prices = prices;
        this.budgets = budgets;
        this.maxItems = maxItems;
    }

    public static class BadBatchException extends RuntimeException {
        public BadBatchException(String m) { super(m); }
    }

    public static class ProviderSubmitException extends RuntimeException {
        public ProviderSubmitException(String m) { super(m); }
    }

    public record SubmitResult(String parentRunId, String anthropicBatchId, int itemCount) {}

    public SubmitResult submit(UUID tenantId, String tenantName, Agent agent,
                               List<BatchItemRequest> items,
                               String completionWebhook, String completionWebhookToken) {

        if (agent.paused()) throw new BadBatchException("agent paused");
        try {
            budgets.checkOrThrow(tenantId, tenantName, agent.id(), agent.name());
        } catch (BudgetException e) {
            throw new BadBatchException("budget: " + e.code());
        }
        if (agent.outputSchema() == null) throw new BadBatchException("agent missing output_schema");
        if (agent.tools() != null && agent.tools().isArray() && agent.tools().size() > 0) {
            throw new BadBatchException("batched agents must not have tools (single-turn only in v1)");
        }
        if (items == null || items.isEmpty()) throw new BadBatchException("items must be non-empty");
        if (items.size() > maxItems) throw new BadBatchException("items must be ≤ " + maxItems);

        // Snapshot the agent (immutable per parent batch run).
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

        // 1) Parent run
        String parentRunId = newUlid();
        runs.create(parentRunId, tenantId, agent.id(), snap, agent.version(),
                null, "batch", null, completionWebhook, completionWebhookToken);
        runs.markRunning(parentRunId);

        // 2) Validate custom_ids and prepare child runs + batch items
        var seen = new HashSet<String>();
        var batchItems = new ArrayList<BatchItem>(items.size());

        var decision = routing.resolve(tenantName, null, agent.modelPurpose(), null);

        for (var item : items) {
            String customId = item.custom_id();
            if (customId == null || customId.isBlank()) {
                customId = newUlid();
            } else if (!CUSTOM_ID.matcher(customId).matches()) {
                throw new BadBatchException("custom_id must match ^[a-zA-Z0-9_-]{1,64}$: " + customId);
            }
            if (!seen.add(customId)) {
                throw new BadBatchException("duplicate custom_id: " + customId);
            }
            String childRunId = customId;
            JsonNode payload = item.payload() == null ? mapper.createObjectNode() : item.payload();

            runs.create(childRunId, tenantId, agent.id(), snap, agent.version(),
                    parentRunId, "batch_item", payload, null, null);

            // Build the per-item LLM request: agent system + payload-as-user-message
            var msg = mapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", payload.toString());
            var messages = mapper.createArrayNode();
            messages.add(msg);

            var preq = new ProviderRequest(decision.model(), 1024, null,
                    agent.systemPrompt(),
                    toMessagesList(messages),
                    List.of(), null,
                    Map.of("agent_name", agent.name()));
            batchItems.add(new BatchItem(customId, preq));
        }

        // 3) Submit to provider
        var provider = providers.get(decision.provider());
        BatchSubmission sub;
        try {
            sub = provider.submitBatch(batchItems);
        } catch (Exception e) {
            String reason = "anthropic_submit_error: " + e.getMessage();
            for (var bi : batchItems) {
                runs.markTerminal(bi.customId(), "failed", null, reason, null);
            }
            runs.markTerminal(parentRunId, "failed", null, reason, null);
            throw new ProviderSubmitException(reason);
        }

        // 4) Persist the Anthropic batch id on the parent
        runRepo.setAnthropicBatchId(parentRunId, sub.anthropicBatchId());

        return new SubmitResult(parentRunId, sub.anthropicBatchId(), items.size());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMessagesList(JsonNode messages) {
        try {
            return mapper.treeToValue(messages, mapper.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public void finalize(String parentRunId, String anthropicBatchId,
                         java.util.stream.Stream<de.vesterion.vistierie.provider.BatchResult> resultsStream) {
        var parent = runRepo.findById(parentRunId).orElseThrow(
                () -> new IllegalStateException("parent run not found: " + parentRunId));
        JsonNode outputSchema = parent.agentSnapshot().path("output_schema");
        String purpose = parent.agentSnapshot().path("model_purpose").asText();

        int done = 0, failed = 0;
        try (var stream = resultsStream) {
            for (var iter = stream.iterator(); iter.hasNext(); ) {
                var r = iter.next();
                String childId = r.customId();
                var child = runRepo.findById(childId).orElse(null);
                if (child == null) continue;
                // Don't overwrite already-terminal children (e.g. killed mid-batch)
                if (!"queued".equals(child.status()) && !"running".equals(child.status())) continue;

                switch (r.type()) {
                    case "succeeded": {
                        if (r.usage() != null) {
                            long cost = prices.costMicrosBatch(r.model(), r.usage());
                            var pReq = new ProviderRequest(
                                    r.model(), 1024, null,
                                    child.agentSnapshot().path("system_prompt").asText(null),
                                    java.util.List.of(java.util.Map.of(
                                            "role", "user",
                                            "content", child.payload() != null ? child.payload().toString() : "")),
                                    java.util.List.of(), null,
                                    java.util.Map.of("agent_name",
                                            child.agentSnapshot().path("name").asText("")));
                            var pRes = new de.vesterion.vistierie.provider.ProviderResponse(
                                    r.text(), r.stopReason(), r.usage(), r.model());
                            recorder.insertWithBody(new de.vesterion.vistierie.audit.LlmCallRecorder.Row(
                                    newUlid(), parent.tenantId(), child.agentId(), purpose, null,
                                    "anthropic", r.model(), "batch",
                                    r.usage().inputTokens(), r.usage().outputTokens(),
                                    r.usage().cacheCreationInputTokens(), r.usage().cacheReadInputTokens(),
                                    cost, 0, "ok", null,
                                    childId, anthropicBatchId), pReq, pRes);
                        }
                        try {
                            JsonNode validated = schemaValidator.parseAndValidate(
                                    r.text(), outputSchema);
                            runs.markTerminal(childId, "done", validated, null,
                                    summarize(r.text()));
                            done++;
                        } catch (Exception e) {
                            runs.markTerminal(childId, "failed", null,
                                    "output_schema: " + e.getMessage(), null);
                            failed++;
                        }
                        break;
                    }
                    case "errored":
                        runs.markTerminal(childId, "failed", null,
                                "anthropic_error: "
                                        + (r.errorMessage() == null ? "" : r.errorMessage()),
                                null);
                        failed++;
                        break;
                    case "canceled":
                        runs.markTerminal(childId, "failed", null,
                                "anthropic_canceled", null);
                        failed++;
                        break;
                    case "expired":
                        runs.markTerminal(childId, "failed", null,
                                "anthropic_expired_after_24h", null);
                        failed++;
                        break;
                    default:
                        runs.markTerminal(childId, "failed", null,
                                "anthropic_unknown_type:" + r.type(), null);
                        failed++;
                }
            }
        }

        // Aggregate parent
        int totalRows = runRepo.findByParent(parentRunId).size();
        var aggregateOutput = mapper.createObjectNode();
        aggregateOutput.put("items_total", totalRows);
        aggregateOutput.put("items_done", done);
        aggregateOutput.put("items_failed", failed);
        runs.markTerminal(parentRunId, "done", aggregateOutput, null,
                "batch:" + anthropicBatchId + " done=" + done + " failed=" + failed);
    }

    private String summarize(String s) {
        if (s == null) return null;
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    private static String newUlid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
