package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.StringJoiner;

@Component
public class RunSearchIndexer {

    static final int PER_TOOL_OUTPUT_CAP = 1000;
    static final int BODY_CAP = 20_000;

    private final RunRepository runs;
    private final AgentRepository agents;
    private final RunTranscriptRepository transcripts;
    private final RunToolCallRepository toolCalls;
    private final RunSearchRepository search;
    private final ObjectMapper json;

    public RunSearchIndexer(RunRepository runs, AgentRepository agents,
                            RunTranscriptRepository transcripts, RunToolCallRepository toolCalls,
                            RunSearchRepository search, ObjectMapper json) {
        this.runs = runs;
        this.agents = agents;
        this.transcripts = transcripts;
        this.toolCalls = toolCalls;
        this.search = search;
        this.json = json;
    }

    /** Best-effort: never throws into the run-completion path. */
    public void index(String runId) {
        try {
            Run run = runs.findById(runId).orElse(null);
            if (run == null) return;
            var agentName = agents.findById(run.agentId()).map(a -> a.name()).orElse("?");

            var body = new StringJoiner("\n");
            for (var c : transcripts.findCallsByRun(runId)) {
                if (c.responseText() != null && !c.responseText().isBlank()) body.add(c.responseText());
            }
            boolean hasError = "failed".equals(run.status());
            for (var tc : toolCalls.findByRun(runId)) {
                body.add(tc.toolName());
                if (tc.isError()) {
                    hasError = true;
                    if (tc.errorDetail() != null) body.add(tc.errorDetail());
                }
                if (tc.output() != null) {
                    String out = tc.output().toString();
                    body.add(out.length() > PER_TOOL_OUTPUT_CAP ? out.substring(0, PER_TOOL_OUTPUT_CAP) : out);
                }
            }
            if (run.error() != null) body.add(run.error());

            String text = body.toString();
            if (text.length() > BODY_CAP) text = text.substring(0, BODY_CAP);

            search.upsert(runId, run.tenantId(), run.agentId(), agentName,
                    run.status(), hasError, run.startedAt(), text);
        } catch (Exception e) {
            // swallow — search indexing must never break run completion
        }
    }
}
