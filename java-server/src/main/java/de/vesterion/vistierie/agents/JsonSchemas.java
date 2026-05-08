package de.vesterion.vistierie.agents;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

@Component
public class JsonSchemas {
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ObjectMapper translator = new ObjectMapper();

    /** Returns null if schema parses OK, else a human-readable error. */
    public String parseError(JsonNode schemaNode) {
        if (schemaNode == null || !schemaNode.isObject()) return "schema must be an object";
        try {
            var asString = translator.writeValueAsString(schemaNode);
            // Use fasterxml to parse for networknt meta-schema validation
            var fasterxmlMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var fasterxmlNode = fasterxmlMapper.readTree(asString);
            // Validate against the JSON Schema 2020-12 meta-schema
            JsonSchema metaSchema = factory.getSchema(
                    new java.net.URI("https://json-schema.org/draft/2020-12/schema"));
            var errors = metaSchema.validate(fasterxmlNode);
            if (!errors.isEmpty()) {
                return "invalid JSON Schema: " + errors.iterator().next().getMessage();
            }
            return null;
        } catch (Exception e) {
            return "invalid JSON Schema: " + e.getMessage();
        }
    }

    public Set<ValidationMessage> validate(JsonNode schemaNode, JsonNode payload) {
        try {
            var schemaString = translator.writeValueAsString(schemaNode);
            JsonSchema schema = factory.getSchema(schemaString);
            // networknt uses fasterxml jackson; round-trip via JSON string
            var fasterxmlMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var fasterxmlPayload = fasterxmlMapper.readTree(translator.writeValueAsString(payload));
            return schema.validate(fasterxmlPayload);
        } catch (Exception e) {
            throw new RuntimeException("schema validation failed", e);
        }
    }
}
