package de.vesterion.vistierie.testsupport;

import de.vesterion.vistierie.pricing.Usage;
import de.vesterion.vistierie.provider.LlmProvider;
import de.vesterion.vistierie.provider.ProviderRequest;
import de.vesterion.vistierie.provider.ProviderResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StubLlmProvider implements LlmProvider {

    private final ConcurrentLinkedQueue<StubLlmScripts.ScriptedTurn> defaultScript = new ConcurrentLinkedQueue<>();
    private final Map<String, ConcurrentLinkedQueue<StubLlmScripts.ScriptedTurn>> agentScripts = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> failNext =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Test helper — the next complete() call throws this exception, then clears. */
    public void failNextComplete(RuntimeException e) { this.failNext.set(e); }

    private final java.util.concurrent.atomic.AtomicReference<ProviderRequest> lastRequest =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Test helper — the {@link ProviderRequest} from the most recent complete() call. */
    public ProviderRequest lastRequest() { return lastRequest.get(); }

    private final java.util.concurrent.atomic.AtomicInteger batchCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.Map<String, java.util.List<de.vesterion.vistierie.provider.BatchItem>> submittedBatches =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> batchStatus =
            new java.util.concurrent.ConcurrentHashMap<>();   // batchId -> "in_progress" | "ended"
    private final java.util.Map<String, java.util.List<de.vesterion.vistierie.provider.BatchResult>> batchResults =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Test helper — flips a previously submitted batch to "ended" and stages results. */
    public void completeBatch(String batchId, java.util.List<de.vesterion.vistierie.provider.BatchResult> results) {
        batchStatus.put(batchId, "ended");
        batchResults.put(batchId, results);
    }

    /** Test helper — returns the items most recently passed to submitBatch. */
    public java.util.List<de.vesterion.vistierie.provider.BatchItem> lastSubmittedBatch() {
        return submittedBatches.values().stream()
                .reduce((a, b) -> b)
                .orElse(java.util.List.of());
    }

    public StubLlmProvider script(StubLlmScripts.ScriptedTurn... turns) {
        defaultScript.clear();
        for (var t : turns) defaultScript.add(t);
        return this;
    }

    public StubLlmProvider scriptForAgent(String agentName, StubLlmScripts.ScriptedTurn... turns) {
        var q = new ConcurrentLinkedQueue<StubLlmScripts.ScriptedTurn>();
        for (var t : turns) q.add(t);
        agentScripts.put(agentName, q);
        return this;
    }

    public void resetAll() {
        failNext.set(null);
        lastRequest.set(null);
        defaultScript.clear();
        agentScripts.clear();
        submittedBatches.clear();
        batchStatus.clear();
        batchResults.clear();
    }

    @Override public String name() { return "anthropic"; }

    @Override public ProviderResponse complete(ProviderRequest req) {
        lastRequest.set(req);
        RuntimeException pending = failNext.getAndSet(null);
        if (pending != null) throw pending;
        var hint = req.metadata() != null ? (String) req.metadata().get("agent_name") : null;
        var queue = (hint != null && agentScripts.containsKey(hint)) ? agentScripts.get(hint) : defaultScript;
        var turn = queue.poll();
        if (turn == null) {
            // Out of script — return a benign end_turn so tests don't hang
            return new ProviderResponse("", "end_turn", new Usage(1, 1, 0, 0), req.model(),
                    mapper.createArrayNode());
        }
        if (turn.toolUses().isEmpty()) {
            // Text-only turn (end_turn, or a truncated max_tokens turn). Emit one text block
            // and report the SCRIPTED stop reason verbatim.
            String text = turn.text() == null ? "" : turn.text();
            ArrayNode content = mapper.createArrayNode();
            content.add(mapper.createObjectNode().put("type", "text").put("text", text));
            return new ProviderResponse(text, turn.stopReason(), new Usage(10, 5, 0, 0), req.model(), content);
        }
        // tool_use turn
        ArrayNode content = mapper.createArrayNode();
        for (var tu : turn.toolUses()) {
            var node = mapper.createObjectNode();
            node.put("type", "tool_use");
            node.put("id", tu.id());
            node.put("name", tu.name());
            node.set("input", mapper.valueToTree(tu.input()));
            content.add(node);
        }
        return new ProviderResponse("", turn.stopReason(), new Usage(15, 8, 0, 0), req.model(), content);
    }

    @Override public ProviderResponse vision(String model, int maxTokens, String mediaType, String base64, String prompt) {
        return new ProviderResponse("[stub vision]", "end_turn", new Usage(50, 4, 0, 0), model);
    }

    @Override
    public de.vesterion.vistierie.provider.BatchSubmission submitBatch(
            java.util.List<de.vesterion.vistierie.provider.BatchItem> items) {
        String id = "stubbatch_" + batchCounter.incrementAndGet();
        submittedBatches.put(id, items);
        batchStatus.put(id, "in_progress");
        return new de.vesterion.vistierie.provider.BatchSubmission(id, items.size());
    }

    @Override
    public de.vesterion.vistierie.provider.BatchStatus getBatch(String anthropicBatchId) {
        String status = batchStatus.getOrDefault(anthropicBatchId, "in_progress");
        int total = submittedBatches.getOrDefault(anthropicBatchId, java.util.List.of()).size();
        int succeeded = "ended".equals(status) ? total : 0;
        String resultsUrl = "ended".equals(status) ? "stub://" + anthropicBatchId : null;
        return new de.vesterion.vistierie.provider.BatchStatus(
                anthropicBatchId, status, 0, succeeded, 0, 0, 0, resultsUrl);
    }

    @Override
    public java.util.stream.Stream<de.vesterion.vistierie.provider.BatchResult> streamResults(String resultsUrl) {
        String batchId = resultsUrl.substring("stub://".length());
        return batchResults.getOrDefault(batchId, java.util.List.of()).stream();
    }
}
