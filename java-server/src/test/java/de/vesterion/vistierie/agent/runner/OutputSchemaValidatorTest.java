package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.JsonSchemas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputSchemaValidatorTest {
    static final ObjectMapper M = new ObjectMapper();
    OutputSchemaValidator v = new OutputSchemaValidator(new JsonSchemas());

    @Test void validJsonValidates() throws Exception {
        var schema = M.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var output = v.parseAndValidate("{\"x\":\"yes\"}", schema);
        assertThat(output.path("x").asText()).isEqualTo("yes");
    }

    @Test void invalidJsonRejected() throws Exception {
        var schema = M.readTree("{\"type\":\"object\"}");
        assertThatThrownBy(() -> v.parseAndValidate("not json", schema))
                .isInstanceOf(OutputSchemaValidator.SchemaViolation.class)
                .hasMessageContaining("parse");
    }

    @Test void schemaViolationRejected() throws Exception {
        var schema = M.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        assertThatThrownBy(() -> v.parseAndValidate("{\"y\":1}", schema))
                .isInstanceOf(OutputSchemaValidator.SchemaViolation.class)
                .hasMessageContaining("required");
    }
}
