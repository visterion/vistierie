package de.vesterion.vistierie.agents;

import de.vesterion.vistierie.agents.dto.*;
import de.vesterion.vistierie.auth.RequestContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentService svc;
    public AgentController(AgentService svc) { this.svc = svc; }

    @PostMapping
    public ResponseEntity<AgentDetail> create(@Valid @RequestBody CreateAgentRequest req) {
        return ResponseEntity.status(201).body(svc.create(RequestContext.requireTenantId(), req));
    }

    @GetMapping
    public List<AgentSummary> list() { return svc.list(RequestContext.requireTenantId()); }

    @GetMapping("/{name}")
    public AgentDetail get(@PathVariable String name) {
        return svc.get(RequestContext.requireTenantId(), name);
    }

    @PutMapping("/{name}")
    public AgentDetail replace(@PathVariable String name, @Valid @RequestBody UpdateAgentRequest req) {
        return svc.replace(RequestContext.requireTenantId(), name, req);
    }

    @PatchMapping("/{name}")
    public AgentDetail patch(@PathVariable String name, @RequestBody PatchAgentRequest req) {
        return svc.patch(RequestContext.requireTenantId(), name, req);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        svc.delete(RequestContext.requireTenantId(), name);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AgentService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(AgentService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(AgentService.ReferencedException.class)
    public ResponseEntity<Map<String, String>> ref(AgentService.ReferencedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(AgentDefinitionValidator.InvalidDefinitionException.class)
    public ResponseEntity<Map<String, String>> bad(AgentDefinitionValidator.InvalidDefinitionException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
