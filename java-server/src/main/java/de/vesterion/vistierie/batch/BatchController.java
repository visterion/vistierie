package de.vesterion.vistierie.batch;

import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.batch.dto.BatchCreatedResponse;
import de.vesterion.vistierie.batch.dto.CreateBatchRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class BatchController {

    private final AgentRepository agents;
    private final BatchService batchService;

    public BatchController(AgentRepository agents, BatchService batchService) {
        this.agents = agents;
        this.batchService = batchService;
    }

    public static class AgentNotFound extends RuntimeException {
        public AgentNotFound(String n) { super("agent not found: " + n); }
    }

    @PostMapping("/agents/{name}/batch")
    public ResponseEntity<BatchCreatedResponse> submit(@PathVariable String name,
                                                       @RequestBody CreateBatchRequest req) {
        var tenantId = RequestContext.requireTenantId();
        var tenantName = RequestContext.requireTenantName();
        var a = agents.findByName(tenantId, name).orElse(null);
        if (a == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (a.paused()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            var res = batchService.submit(tenantId, tenantName, a, req.items(),
                    req.completion_webhook(), req.completion_webhook_token());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BatchCreatedResponse(
                    res.parentRunId(), a.name(), a.version(), "queued",
                    res.itemCount(), res.anthropicBatchId()));
        } catch (BatchService.BadBatchException e) {
            return ResponseEntity.badRequest().build();
        } catch (BatchService.ProviderSubmitException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
