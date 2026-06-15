package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.transcript.RunTranscriptRepository.CallRow;
import de.vesterion.vistierie.transcript.TranscriptDtos.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class RunTranscriptService {

    /** Per-field truncation cap for compact view (chars of serialized JSON / text). */
    static final int COMPACT_CAP = 2000;

    private final RunTranscriptRepository transcripts;
    private final RunToolCallRepository toolCalls;
    private final ObjectMapper json;

    public RunTranscriptService(RunTranscriptRepository transcripts,
                                RunToolCallRepository toolCalls, ObjectMapper json) {
        this.transcripts = transcripts;
        this.toolCalls = toolCalls;
        this.json = json;
    }

    public TranscriptDto buildTranscript(String runId, String agent, String status,
                                         Instant startedAt, Instant finishedAt,
                                         JsonNode output, String error,
                                         String view, Integer turnsFrom, Integer turnsTo) {
        boolean full = "full".equals(view);
        var calls = transcripts.findCallsByRun(runId);
        var tcByCall = new LinkedHashMap<String, List<RunToolCall>>();
        for (var tc : toolCalls.findByRun(runId)) {
            tcByCall.computeIfAbsent(tc.llmCallId() == null ? "" : tc.llmCallId(), k -> new ArrayList<>()).add(tc);
        }

        var turns = new ArrayList<TurnDto>();
        String model = null;
        for (int i = 0; i < calls.size(); i++) {
            if (turnsFrom != null && i < turnsFrom) continue;
            if (turnsTo != null && i > turnsTo) break;
            var c = calls.get(i);
            model = c.model();
            var tcs = tcByCall.getOrDefault(c.callId(), List.of());

            var toolUses = new ArrayList<ToolUseDto>();
            if (c.responseContentJson() != null && c.responseContentJson().isArray()) {
                for (var blk : c.responseContentJson()) {
                    if ("tool_use".equals(blk.path("type").asText())) {
                        toolUses.add(new ToolUseDto(blk.path("id").asText(), blk.path("name").asText(),
                                blk.get("input")));
                    }
                }
            }
            var toolCallDtos = new ArrayList<ToolCallDto>();
            for (var tc : tcs) {
                toolCallDtos.add(new ToolCallDto(tc.toolUseId(), tc.toolName(), tc.toolType(),
                        full ? tc.input() : cap(tc.input()),
                        full ? tc.output() : cap(tc.output()),
                        tc.isError(), tc.errorDetail()));
            }
            String stopReason = tcs.isEmpty() ? "end_turn" : "tool_use";
            String text = c.responseText();
            if (!full && text != null && text.length() > COMPACT_CAP) {
                text = text.substring(0, COMPACT_CAP) + "…";
            }
            JsonNode inputMessages = full && c.requestJson() != null ? c.requestJson().path("messages") : null;
            JsonNode rawContent = full ? c.responseContentJson() : null;
            turns.add(new TurnDto(i, inputMessages, text, stopReason, toolUses,
                    new Tokens(c.inputTokens(), c.outputTokens(), c.cacheCreate(), c.cacheRead()),
                    toolCallDtos, rawContent));
        }
        return new TranscriptDto(runId, agent, status, model, calls.size(),
                startedAt, finishedAt, turns, output, error);
    }

    public DigestDto buildDigest(String runId, String agent, String status, String model,
                                 JsonNode output, String error) {
        var calls = transcripts.findCallsByRun(runId);
        long tokenTotal = 0;
        String resolvedModel = model;
        for (var c : calls) { tokenTotal += c.inputTokens() + c.outputTokens(); resolvedModel = c.model(); }

        var counts = new LinkedHashMap<String, int[]>(); // name -> [count, errorCount]
        for (var tc : toolCalls.findByRun(runId)) {
            var a = counts.computeIfAbsent(tc.toolName(), k -> new int[2]);
            a[0]++; if (tc.isError()) a[1]++;
        }
        var tools = new ArrayList<ToolUsageDto>();
        for (var e : counts.entrySet()) tools.add(new ToolUsageDto(e.getKey(), e.getValue()[0], e.getValue()[1]));

        return new DigestDto(runId, agent, status, resolvedModel, calls.size(), tokenTotal,
                tools, capOutput(output), error);
    }

    /** Truncate a JSON node by serialized length, returning a marker object when oversized. */
    private JsonNode cap(JsonNode node) {
        if (node == null) return null;
        String s;
        try { s = json.writeValueAsString(node); } catch (Exception e) { return node; }
        if (s.length() <= COMPACT_CAP) return node;
        ObjectNode marker = json.createObjectNode();
        marker.put("truncated", true);
        marker.put("full_chars", s.length());
        marker.put("preview", s.substring(0, COMPACT_CAP));
        return marker;
    }

    private JsonNode capOutput(JsonNode output) { return cap(output); }

    /** Fetch a single tool call untruncated (drill-down). */
    public RunToolCall toolCall(String runId, String toolUseId) {
        return toolCalls.findByRunAndToolUseId(runId, toolUseId).orElse(null);
    }
}
