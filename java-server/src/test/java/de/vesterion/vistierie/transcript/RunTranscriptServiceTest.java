package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.transcript.RunTranscriptRepository.CallRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunTranscriptServiceTest {

    final ObjectMapper mapper = new ObjectMapper();

    private CallRow toolTurn(String callId) throws Exception {
        var content = mapper.createArrayNode();
        var tu = mapper.createObjectNode();
        tu.put("type", "tool_use"); tu.put("id", "toolu_a"); tu.put("name", "finnhub");
        tu.set("input", mapper.createObjectNode().put("q", "AAPL"));
        content.add(tu);
        return new CallRow(callId, "claude-haiku-4-5", 15, 8, 0, 0,
                mapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),
                "", content);
    }

    private CallRow endTurn(String callId) throws Exception {
        var content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type", "text").put("text", "all done"));
        return new CallRow(callId, "claude-haiku-4-5", 10, 5, 0, 0,
                mapper.readTree("{\"messages\":[]}"), "all done", content);
    }

    private RunToolCall toolRow(String callId) throws Exception {
        return new RunToolCall("t1", "RUN", UUID.randomUUID(), callId, 0, "toolu_a", "finnhub", "http",
                mapper.readTree("{\"q\":\"AAPL\"}"), mapper.readTree("{\"count\":0}"), false, null, Instant.now());
    }

    private RunTranscriptService service(List<CallRow> calls, List<RunToolCall> tcs) {
        var transRepo = mock(RunTranscriptRepository.class);
        var tcRepo = mock(RunToolCallRepository.class);
        when(transRepo.findCallsByRun("RUN")).thenReturn(calls);
        when(tcRepo.findByRun("RUN")).thenReturn(tcs);
        return new RunTranscriptService(transRepo, tcRepo, new ObjectMapper());
    }

    @Test void compactOmitsInputMessagesAndJoinsToolCalls() throws Exception {
        var svc = service(List.of(toolTurn("C0"), endTurn("C1")), List.of(toolRow("C0")));
        var t = svc.buildTranscript("RUN", "agentX", "done",
                Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T00:01:00Z"),
                mapper.readTree("{\"x\":\"done\"}"), null, "compact", null, null);

        assertThat(t.turn_count()).isEqualTo(2);
        assertThat(t.turns().get(0).llm_input_messages()).isNull();
        assertThat(t.turns().get(0).stop_reason()).isEqualTo("tool_use");
        assertThat(t.turns().get(0).tool_calls()).hasSize(1);
        assertThat(t.turns().get(0).tool_calls().get(0).output().path("count").asInt()).isZero();
        assertThat(t.turns().get(1).stop_reason()).isEqualTo("end_turn");
        assertThat(t.turns().get(1).text()).isEqualTo("all done");
        assertThat(t.turns().get(0).raw_response_content()).isNull();
    }

    @Test void fullIncludesInputMessagesAndRawContent() throws Exception {
        var svc = service(List.of(toolTurn("C0")), List.of(toolRow("C0")));
        var t = svc.buildTranscript("RUN", "agentX", "running", Instant.now(), null,
                null, null, "full", null, null);
        assertThat(t.turns().get(0).llm_input_messages()).isNotNull();
        assertThat(t.turns().get(0).raw_response_content()).isNotNull();
    }

    @Test void digestAggregatesToolsAndTokens() throws Exception {
        var svc = service(List.of(toolTurn("C0"), endTurn("C1")), List.of(toolRow("C0")));
        var d = svc.buildDigest("RUN", "agentX", "done", null,
                mapper.readTree("{\"x\":\"done\"}"), null);
        assertThat(d.turn_count()).isEqualTo(2);
        assertThat(d.token_total()).isEqualTo(15 + 8 + 10 + 5);
        assertThat(d.tools()).hasSize(1);
        assertThat(d.tools().get(0).name()).isEqualTo("finnhub");
        assertThat(d.tools().get(0).count()).isEqualTo(1);
        assertThat(d.tools().get(0).error_count()).isZero();
    }
}
