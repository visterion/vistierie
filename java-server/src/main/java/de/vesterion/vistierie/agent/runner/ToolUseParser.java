package de.vesterion.vistierie.agent.runner;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolUseParser {

    public record Block(String id, String name, JsonNode input) {}

    public List<Block> parse(JsonNode contentArray) {
        var out = new ArrayList<Block>();
        if (contentArray == null || !contentArray.isArray()) return out;
        for (JsonNode block : contentArray) {
            if ("tool_use".equals(block.path("type").asText())) {
                out.add(new Block(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        block.path("input")));
            }
        }
        return out;
    }
}
