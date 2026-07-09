package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.agents.dto.ToolDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class AgentDefinitionValidator {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]+$");
    private final JsonSchemas schemas;

    public AgentDefinitionValidator(JsonSchemas schemas) { this.schemas = schemas; }

    public void validateName(String name) {
        if (name == null || name.length() > 64 || !NAME.matcher(name).matches()) {
            throw new InvalidDefinitionException(
                    "name must match ^[a-z0-9-]+$ and be ≤64 chars: " + name);
        }
    }

    public void validateTool(ToolDef t, List<String> sameTenantAgentNames) {
        if (t.name() == null || t.name().isBlank()) {
            throw new InvalidDefinitionException("tool name required");
        }
        boolean hasWebhook = t.webhook_url() != null;
        boolean hasSubagent = "subagent".equals(t.type());
        boolean hasMcp = "mcp".equals(t.type());
        int kinds = (hasWebhook ? 1 : 0) + (hasSubagent ? 1 : 0) + (hasMcp ? 1 : 0);
        if (kinds != 1) {
            throw new InvalidDefinitionException(
                    "tool '" + t.name() + "' must have exactly one of webhook_url, type=subagent, type=mcp");
        }
        if (hasSubagent) {
            if (t.target_agent() == null || t.target_agent().isBlank()) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' subagent requires target_agent");
            }
            if (!sameTenantAgentNames.contains(t.target_agent())) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' target_agent '" + t.target_agent() + "' not found in tenant");
            }
        }
        if (hasWebhook) {
            var u = t.webhook_url();
            if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' webhook_url must be http(s)");
            }
        }
        if (hasMcp) {
            var u = t.mcp_server_url();
            if (u == null || u.isBlank()) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' mcp requires mcp_server_url");
            }
            if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' mcp_server_url must be http(s)");
            }
            if (t.mcp_auth_ref() != null) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' unsupported mcp_auth_ref (v1 supports only omitted; token resolved by server URL from mcp_credentials): "
                                + t.mcp_auth_ref());
            }
            if (t.mcp_timeout_seconds() != null && t.mcp_timeout_seconds() <= 0) {
                throw new InvalidDefinitionException(
                        "tool '" + t.name() + "' mcp_timeout_seconds must be > 0");
            }
        }
        var schemaErr = schemas.parseError(t.input_schema());
        if (schemaErr != null) {
            throw new InvalidDefinitionException(
                    "tool '" + t.name() + "' input_schema: " + schemaErr);
        }
    }

    public void validateMcpCredentials(List<ToolDef> tools, tools.jackson.databind.JsonNode mcpCredentials) {
        for (var t : tools) {
            if (t.isMcpTool()) {
                String url = t.mcp_server_url();
                if (url != null && (mcpCredentials == null || !mcpCredentials.has(url))) {
                    throw new InvalidDefinitionException(
                            "mcp tool '" + t.name() + "' references server '" + url
                                    + "' with no entry in mcp_credentials");
                }
            }
        }
    }

    public void validateOutputSchemaIfPresent(tools.jackson.databind.JsonNode outputSchema) {
        if (outputSchema == null) return;
        var err = schemas.parseError(outputSchema);
        if (err != null) {
            throw new InvalidDefinitionException("output_schema: " + err);
        }
    }

    public void validateSchedule(String cron) {
        if (cron == null || cron.isBlank()) return;
        try {
            org.springframework.scheduling.support.CronExpression.parse(cron.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidDefinitionException(
                    "schedule is not a valid Spring 6-field cron expression: " + e.getMessage());
        }
    }

    public void validateStreaming(String eventSourceUrl, String schedule, Integer sessionDurationSeconds) {
        if (sessionDurationSeconds == null) return;
        if (sessionDurationSeconds <= 0) {
            throw new InvalidDefinitionException(
                    "session_duration_seconds must be > 0");
        }
        if (eventSourceUrl == null || eventSourceUrl.isBlank()) {
            throw new InvalidDefinitionException(
                    "event_source_url is required when session_duration_seconds is set");
        }
        if (schedule == null || schedule.isBlank()) {
            throw new InvalidDefinitionException(
                    "schedule is required when session_duration_seconds is set (used as session-open trigger)");
        }
    }

    public static class InvalidDefinitionException extends RuntimeException {
        public InvalidDefinitionException(String m) { super(m); }
    }
}
