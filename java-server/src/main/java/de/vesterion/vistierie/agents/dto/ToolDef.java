package de.vesterion.vistierie.agents.dto;

import tools.jackson.databind.JsonNode;

public record ToolDef(
        String name,
        String description,
        JsonNode input_schema,
        String type,                 // "subagent" | "mcp" | null (http)
        String target_agent,         // for subagent
        String webhook_url,          // for http tool
        Integer webhook_timeout_seconds,
        String mcp_server_url,       // for mcp tool: base URL, e.g. "http://agora:8080"
        String mcp_tool_name,        // for mcp tool: remote tool name; defaults to `name`
        String mcp_auth_ref,         // for mcp tool: forward-compat marker; v1 supports ONLY null
        Integer mcp_timeout_seconds  // for mcp tool: per-call timeout
) {
    public ToolDef(String name, String description, JsonNode input_schema, String type,
                    String target_agent, String webhook_url, Integer webhook_timeout_seconds) {
        this(name, description, input_schema, type, target_agent, webhook_url,
             webhook_timeout_seconds, null, null, null, null);
    }
    public boolean isSubagent() { return "subagent".equals(type); }
    public boolean isMcpTool()  { return "mcp".equals(type); }
    public boolean isHttpTool() { return webhook_url != null && type == null; }
    public String resolvedMcpToolName() { return mcp_tool_name != null ? mcp_tool_name : name; }
}
