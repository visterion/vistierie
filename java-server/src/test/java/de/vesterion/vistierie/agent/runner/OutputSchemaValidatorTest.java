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

    static final String NUM_SCHEMA =
        "{\"type\":\"object\",\"required\":[\"x\"],\"properties\":{\"x\":{\"type\":\"number\"}}}";

    @Test void fencedJsonWithLang() throws Exception {
        var schema = M.readTree(NUM_SCHEMA);
        var out = v.parseAndValidate("```json\n{\"x\":1}\n```", schema);
        assertThat(out.path("x").asInt()).isEqualTo(1);
    }

    @Test void fencedJsonNoLang() throws Exception {
        var schema = M.readTree(NUM_SCHEMA);
        var out = v.parseAndValidate("```\n{\"x\":1}\n```", schema);
        assertThat(out.path("x").asInt()).isEqualTo(1);
    }

    @Test void prosePrefix() throws Exception {
        var schema = M.readTree(NUM_SCHEMA);
        var out = v.parseAndValidate("Here is the JSON: {\"x\":1}", schema);
        assertThat(out.path("x").asInt()).isEqualTo(1);
    }

    @Test void proseAndFence() throws Exception {
        var schema = M.readTree(NUM_SCHEMA);
        var out = v.parseAndValidate("Result:\n```json\n{\"x\":1}\n```\nDone.", schema);
        assertThat(out.path("x").asInt()).isEqualTo(1);
    }

    @Test void bracesInProseBeforeJson() throws Exception {
        var schema = M.readTree(NUM_SCHEMA);
        var out = v.parseAndValidate("Note {see below}: {\"x\":1}", schema);
        assertThat(out.path("x").asInt()).isEqualTo(1);
    }

    @Test void stringContainingBraceProseWrapped() throws Exception {
        var schema = M.readTree(
            "{\"type\":\"object\",\"required\":[\"note\"],\"properties\":{\"note\":{\"type\":\"string\"}}}");
        var out = v.parseAndValidate("prefix {\"note\":\"a } b\"} suffix", schema);
        assertThat(out.path("note").asText()).isEqualTo("a } b");
    }

    @Test void fencedArray() throws Exception {
        var schema = M.readTree("{\"type\":\"array\"}");
        var out = v.parseAndValidate("```json\n[1,2]\n```", schema);
        assertThat(out.isArray()).isTrue();
        assertThat(out.size()).isEqualTo(2);
    }

    @Test void totallyNonJsonStillParseError() throws Exception {
        var schema = M.readTree(NUM_SCHEMA);
        assertThatThrownBy(() -> v.parseAndValidate("the model refused", schema))
            .isInstanceOf(OutputSchemaValidator.SchemaViolation.class)
            .hasMessageStartingWith("parse:");
    }
}
