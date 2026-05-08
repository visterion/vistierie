package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.agents.Agent;
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
    private final int maxItems;

    public BatchService(RunStore runs, RunRepository runRepo, RoutingResolver routing,
                        ProviderRegistry providers, ObjectMapper mapper,
                        @Value("${vistierie.agents.batch.max-items:10000}") int maxItems) {
        this.runs = runs;
        this.runRepo = runRepo;
        this.routing = routing;
        this.providers = providers;
        this.mapper = mapper;
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
        snap.put("webhook_token", agent.webhookToken());

        // 1) Parent run
        String parentRunId = newUlid();
        runs.create(parentRunId, tenantId, agent.id(), snap, agent.version(),
                null, "batch", null, completionWebhook, completionWebhookToken);
        runs.markRunning(parentRunId);

        // 2) Validate custom_ids and prepare child runs + batch items
        var seen = new HashSet<String>();
        var batchItems = new ArrayList<BatchItem>(items.size());

        var decision = routing.resolve(tenantName, agent.modelPurpose(), null);

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

    private static String newUlid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
