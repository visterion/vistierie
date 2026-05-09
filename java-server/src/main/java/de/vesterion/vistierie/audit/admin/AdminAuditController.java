package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.audit.admin.dto.AdminLlmCallDetail;
import de.vesterion.vistierie.audit.admin.dto.AdminLlmCallRow;
import de.vesterion.vistierie.audit.admin.dto.AdminRunSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminAuditController {

    private final AdminAuditRepository repo;

    public AdminAuditController(AdminAuditRepository repo) { this.repo = repo; }

    @GetMapping("/runs")
    public Map<String, Object> runs(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit > 200) limit = 200;
        if (limit < 1) limit = 50;
        if (offset < 0) offset = 0;
        List<AdminRunSummary> items = repo.findRuns(tenant, agent, status, from, to, limit, offset);
        long total = repo.countRuns(tenant, agent, status, from, to);
        return Map.of("items", items, "total", total, "limit", limit, "offset", offset);
    }

    @GetMapping("/llm-calls")
    public Map<String, Object> llmCalls(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String realm,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false, name = "run_id") String runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit > 200) limit = 200;
        if (limit < 1) limit = 50;
        if (offset < 0) offset = 0;
        List<AdminLlmCallRow> items = repo.findLlmCalls(tenant, realm, purpose, provider, model, endpoint,
                status, runId, from, to, limit, offset);
        return Map.of("items", items, "limit", limit, "offset", offset);
    }

    @GetMapping("/llm-calls/{id}")
    public AdminLlmCallDetail llmCallDetail(@PathVariable String id) {
        return repo.findCallDetail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "call not found"));
    }
}
