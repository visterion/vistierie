package de.vesterion.vistierie.agents;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Component
public class JsonSchemas {
    private final SchemaRegistry registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** Returns null if schema parses OK, else a human-readable error. */
    public String parseError(JsonNode schemaNode) {
        if (schemaNode == null || !schemaNode.isObject()) return "schema must be an object";
        try {
            Schema metaSchema = registry.getSchema(
                    SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
            List<Error> errors = metaSchema.validate(schemaNode);
            if (!errors.isEmpty()) {
                return "invalid JSON Schema: " + errors.get(0).getMessage();
            }
            return null;
        } catch (Exception e) {
            return "invalid JSON Schema: " + e.getMessage();
        }
    }

    public List<Error> validate(JsonNode schemaNode, JsonNode payload) {
        try {
            Schema schema = registry.getSchema(schemaNode);
            return schema.validate(payload);
        } catch (Exception e) {
            throw new RuntimeException("schema validation failed", e);
        }
    }
}
