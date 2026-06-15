package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.runs.RunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminTranscriptController {

    private final RunRepository runs;
    private final AgentRepository agents;
    private final RunTranscriptService service;

    public AdminTranscriptController(RunRepository runs, AgentRepository agents, RunTranscriptService service) {
        this.runs = runs;
        this.agents = agents;
        this.service = service;
    }

    @GetMapping("/runs/{id}/transcript")
    public ResponseEntity<?> transcript(@PathVariable String id,
                                        @RequestParam(defaultValue = "compact") String view,
                                        @RequestParam(name = "turns_from", required = false) Integer turnsFrom,
                                        @RequestParam(name = "turns_to", required = false) Integer turnsTo) {
        var run = runs.findById(id).orElse(null);
        if (run == null) return ResponseEntity.notFound().build();
        var agentName = agents.findById(run.agentId()).map(a -> a.name()).orElse("?");
        if ("digest".equals(view)) {
            return ResponseEntity.ok(service.buildDigest(run.id(), agentName, run.status(), null,
                    run.output(), run.error()));
        }
        return ResponseEntity.ok(service.buildTranscript(run.id(), agentName, run.status(),
                run.startedAt(), run.finishedAt(), run.output(), run.error(), view, turnsFrom, turnsTo));
    }
}
