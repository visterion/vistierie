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
        if (hasWebhook == hasSubagent) {
            throw new InvalidDefinitionException(
                    "tool '" + t.name() + "' must have either webhook_url or type=subagent, not both/neither");
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
        var schemaErr = schemas.parseError(t.input_schema());
        if (schemaErr != null) {
            throw new InvalidDefinitionException(
                    "tool '" + t.name() + "' input_schema: " + schemaErr);
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

    public static class InvalidDefinitionException extends RuntimeException {
        public InvalidDefinitionException(String m) { super(m); }
    }
}
