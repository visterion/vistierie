package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.runs.dto.CreateRunRequest;
import de.vesterion.vistierie.runs.dto.RunCreatedResponse;
import de.vesterion.vistierie.runs.dto.RunDetail;
import de.vesterion.vistierie.runs.dto.RunEventDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;

@RestController
public class RunController {

    private final AgentRepository agents;
    private final AgentDispatcher dispatcher;
    private final RunRepository runs;
    private final RunEventRecorder events;

    public RunController(AgentRepository agents, AgentDispatcher dispatcher,
                         RunRepository runs, RunEventRecorder events) {
        this.agents = agents;
        this.dispatcher = dispatcher;
        this.runs = runs;
        this.events = events;
    }

    @PostMapping("/agents/{name}/run")
    public ResponseEntity<RunCreatedResponse> trigger(@PathVariable String name,
                                                       @RequestBody CreateRunRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var a = agents.findByName(tenantId, name)
                .orElseThrow(() -> new RuntimeException("agent not found: " + name));
        if (a.paused()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        var runId = dispatcher.trigger(tenantId, a, "manual",
                req.payload(), req.completion_webhook(), req.completion_webhook_token());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                new RunCreatedResponse(runId, a.name(), a.version(), "queued"));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<RunDetail> get(@PathVariable String id) {
        var tenantId = RequestContext.requireTenantId();
        var r = runs.findById(id).orElse(null);
        if (r == null || !r.tenantId().equals(tenantId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDetail(r));
    }

    @GetMapping("/runs")
    public List<RunDetail> list() {
        var tenantId = RequestContext.requireTenantId();
        return runs.findByTenant(tenantId, 100).stream().map(this::toDetail).toList();
    }

    @GetMapping("/runs/{id}/events")
    public ResponseEntity<List<RunEventDto>> events(@PathVariable String id) {
        var tenantId = RequestContext.requireTenantId();
        var r = runs.findById(id).orElse(null);
        if (r == null || !r.tenantId().equals(tenantId)) return ResponseEntity.notFound().build();
        var list = events.byRun(id).stream()
                .map(e -> new RunEventDto(e.id(), e.ts(), e.level(), e.type(), e.payload()))
                .toList();
        return ResponseEntity.ok(list);
    }

    private RunDetail toDetail(Run r) {
        var agentName = agents.findById(r.agentId()).map(a -> a.name()).orElse("?");
        var children = runs.findByParent(r.id());
        var counts = new HashMap<String, Integer>();
        for (var c : children) counts.merge(c.status(), 1, Integer::sum);
        return new RunDetail(
                r.id(), agentName, r.agentVersion(), r.trigger(), r.status(),
                r.startedAt(), r.finishedAt(), r.summary(), r.output(), r.error(),
                r.parentRunId(), counts);
    }
}
