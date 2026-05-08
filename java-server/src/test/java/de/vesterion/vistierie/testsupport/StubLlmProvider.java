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

    public void resetAll() { defaultScript.clear(); agentScripts.clear(); }

    @Override public String name() { return "anthropic"; }

    @Override public ProviderResponse complete(ProviderRequest req) {
        var hint = req.metadata() != null ? (String) req.metadata().get("agent_name") : null;
        var queue = (hint != null && agentScripts.containsKey(hint)) ? agentScripts.get(hint) : defaultScript;
        var turn = queue.poll();
        if (turn == null) {
            // Out of script — return a benign end_turn so tests don't hang
            return new ProviderResponse("", "end_turn", new Usage(1, 1, 0, 0), req.model(),
                    mapper.createArrayNode());
        }
        if ("end_turn".equals(turn.stopReason())) {
            ArrayNode content = mapper.createArrayNode();
            content.add(mapper.createObjectNode().put("type", "text").put("text", turn.text() == null ? "" : turn.text()));
            return new ProviderResponse(turn.text() == null ? "" : turn.text(),
                    "end_turn", new Usage(10, 5, 0, 0), req.model(), content);
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
        return new ProviderResponse("", "tool_use", new Usage(15, 8, 0, 0), req.model(), content);
    }

    @Override public ProviderResponse vision(String model, int maxTokens, String mediaType, String base64, String prompt) {
        return new ProviderResponse("[stub vision]", "end_turn", new Usage(50, 4, 0, 0), model);
    }
}
