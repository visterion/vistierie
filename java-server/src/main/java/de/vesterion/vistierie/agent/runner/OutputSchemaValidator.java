package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.JsonSchemas;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutputSchemaValidator {

    public static class SchemaViolation extends RuntimeException {
        public SchemaViolation(String m) { super(m); }
    }

    private final JsonSchemas schemas;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutputSchemaValidator(JsonSchemas schemas) { this.schemas = schemas; }

    public JsonNode parseAndValidate(String text, JsonNode schema) {
        JsonNode parsed;
        try { parsed = mapper.readTree(text); }
        catch (Exception e) { throw new SchemaViolation("parse: " + e.getMessage()); }
        var errors = schemas.validate(schema, parsed);
        if (!errors.isEmpty()) {
            var msg = errors.stream().map(Object::toString)
                    .reduce((a, b) -> a + "; " + b).orElse("");
            throw new SchemaViolation(msg);
        }
        return parsed;
    }
}
