package de.vesterion.vistierie.agent.runner;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ToolUseParserTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test void extractsToolUseBlocks() throws Exception {
        var content = M.readTree("""
            [
              {"type":"text","text":"thinking..."},
              {"type":"tool_use","id":"toolu_1","name":"cell.read","input":{"id":"c1"}},
              {"type":"tool_use","id":"toolu_2","name":"cell.search","input":{"q":"x"}}
            ]
            """);
        var p = new ToolUseParser();
        var blocks = p.parse(content);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).id()).isEqualTo("toolu_1");
        assertThat(blocks.get(0).name()).isEqualTo("cell.read");
        assertThat(blocks.get(0).input().path("id").asText()).isEqualTo("c1");
        assertThat(blocks.get(1).name()).isEqualTo("cell.search");
    }

    @Test void emptyWhenNoToolUses() throws Exception {
        var content = M.readTree("[{\"type\":\"text\",\"text\":\"hello\"}]");
        var p = new ToolUseParser();
        assertThat(p.parse(content)).isEmpty();
    }
}
