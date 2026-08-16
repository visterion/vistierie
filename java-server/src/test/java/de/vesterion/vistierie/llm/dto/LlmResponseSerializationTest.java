package de.vesterion.vistierie.llm.dto;

import de.vesterion.vistierie.pricing.Usage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for finding #2: every existing toolless caller of /llm/complete,
 * /llm/vision and /llm/vision-multi must see a bit-for-bit unchanged response — no
 * "content_blocks" key at all — while a tool-carrying request still gets the array.
 */
class LlmResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void toollessResponseOmitsContentBlocksKey() {
        var res = new LlmResponse("ok", "end_turn", new Usage(1, 2, 0, 0),
                "anthropic", "claude-haiku-4-5", 10L, "call-1", null);

        String json = mapper.writeValueAsString(res);

        assertThat(json).doesNotContain("content_blocks");
    }

    @Test void toolCarryingResponseIncludesContentBlocksKey() {
        var blocks = mapper.createArrayNode();
        blocks.addObject().put("type", "tool_use").put("id", "tu1").put("name", "search");
        var res = new LlmResponse("", "tool_use", new Usage(1, 2, 0, 0),
                "anthropic", "claude-haiku-4-5", 10L, "call-2", blocks);

        String json = mapper.writeValueAsString(res);

        assertThat(json).contains("\"content_blocks\"");
        assertThat(json).contains("\"tu1\"");
    }
}
