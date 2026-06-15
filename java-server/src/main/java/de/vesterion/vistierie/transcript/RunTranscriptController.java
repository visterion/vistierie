package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.runs.Run;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.transcript.TranscriptDtos.ToolCallDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RunTranscriptController {

    private final RunRepository runs;
    private final AgentRepository agents;
    private final RunTranscriptService service;

    public RunTranscriptController(RunRepository runs, AgentRepository agents, RunTranscriptService service) {
        this.runs = runs;
        this.agents = agents;
        this.service = service;
    }

    @GetMapping("/runs/{id}/transcript")
    public ResponseEntity<?> transcript(@PathVariable String id,
                                        @RequestParam(defaultValue = "compact") String view,
                                        @RequestParam(name = "turns_from", required = false) Integer turnsFrom,
                                        @RequestParam(name = "turns_to", required = false) Integer turnsTo) {
        var tenantId = RequestContext.requireTenantId();
        var run = runs.findById(id).orElse(null);
        if (run == null || !run.tenantId().equals(tenantId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(render(run, view, turnsFrom, turnsTo));
    }

    @GetMapping("/runs/{id}/tool-calls/{toolUseId}")
    public ResponseEntity<ToolCallDto> toolCall(@PathVariable String id, @PathVariable String toolUseId) {
        var tenantId = RequestContext.requireTenantId();
        var run = runs.findById(id).orElse(null);
        if (run == null || !run.tenantId().equals(tenantId)) return ResponseEntity.notFound().build();
        var tc = service.toolCall(id, toolUseId);
        if (tc == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new ToolCallDto(tc.toolUseId(), tc.toolName(), tc.toolType(),
                tc.input(), tc.output(), tc.isError(), tc.errorDetail()));
    }

    private Object render(Run run, String view, Integer from, Integer to) {
        var agentName = agents.findById(run.agentId()).map(a -> a.name()).orElse("?");
        if ("digest".equals(view)) {
            return service.buildDigest(run.id(), agentName, run.status(), null, run.output(), run.error());
        }
        return service.buildTranscript(run.id(), agentName, run.status(),
                run.startedAt(), run.finishedAt(), run.output(), run.error(), view, from, to);
    }
}
