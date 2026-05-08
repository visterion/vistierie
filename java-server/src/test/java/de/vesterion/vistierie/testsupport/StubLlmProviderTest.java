package de.vesterion.vistierie.testsupport;

import de.vesterion.vistierie.provider.ProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static de.vesterion.vistierie.testsupport.StubLlmScripts.Turn;
import static org.assertj.core.api.Assertions.assertThat;

class StubLlmProviderTest {
    @Test void scriptedTurnsConsumedInOrder() {
        var stub = new StubLlmProvider();
        stub.script(
                Turn.text("hi"),
                Turn.toolUses(Turn.toolUse("cell.search", Map.of("q", "x"))),
                Turn.endTurn("{\"tunnels\":[]}")
        );
        var req = new ProviderRequest("m", 100, null, null,
                List.of(Map.of("role", "user", "content", "hi")), null, null, null);
        var r1 = stub.complete(req);
        assertThat(r1.text()).isEqualTo("hi");
        assertThat(r1.stopReason()).isEqualTo("end_turn");

        var r2 = stub.complete(req);
        assertThat(r2.stopReason()).isEqualTo("tool_use");
        assertThat(r2.contentBlocks()).isNotNull();
        assertThat(r2.contentBlocks().size()).isEqualTo(1);
        assertThat(r2.contentBlocks().get(0).path("name").asText()).isEqualTo("cell.search");

        var r3 = stub.complete(req);
        assertThat(r3.text()).isEqualTo("{\"tunnels\":[]}");
        assertThat(r3.stopReason()).isEqualTo("end_turn");
    }
}
