package de.vesterion.vistierie.agents.dto;

import tools.jackson.databind.JsonNode;

public record ToolDef(
        String name,
        String description,
        JsonNode input_schema,
        String type,            // "subagent" or null
        String target_agent,    // for subagent
        String webhook_url,     // for http tool
        Integer webhook_timeout_seconds
) {
    public boolean isSubagent() { return "subagent".equals(type); }
    public boolean isHttpTool() { return webhook_url != null && type == null; }
}
