package de.vesterion.vistierie.agent.runner;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultToolFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultToolFactory factory = new ResultToolFactory();

    @Test
    void buildsToolDefinitionFromSchema() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","required":["verdicts"],
                 "properties":{"verdicts":{"type":"array"}}}
                """);

        Map<String, Object> def = factory.build(schema);

        assertEquals("submit_result", def.get("name"));
        assertEquals("submit_result", ResultToolFactory.TOOL_NAME);
        assertInstanceOf(String.class, def.get("description"));
        assertFalse(((String) def.get("description")).isBlank());
        assertEquals(schema, def.get("input_schema"));
    }

    @Test
    void rejectsNullSchema() {
        assertThrows(IllegalArgumentException.class, () -> factory.build(null));
    }
}
