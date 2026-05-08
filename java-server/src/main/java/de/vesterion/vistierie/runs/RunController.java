package de.vesterion.vistierie.runs;

import de.vesterion.vistierie.agent.runner.AgentDispatcher;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.runs.dto.CreateRunRequest;
import de.vesterion.vistierie.runs.dto.RunCreatedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RunController {

    private final AgentRepository agents;
    private final AgentDispatcher dispatcher;

    public RunController(AgentRepository agents, AgentDispatcher dispatcher) {
        this.agents = agents;
        this.dispatcher = dispatcher;
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
}
