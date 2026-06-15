package de.vesterion.vistierie.transcript;

import de.vesterion.vistierie.auth.RequestContext;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class RunSearchController {

    private final RunSearchRepository search;
    private final TenantRepository tenants;

    public RunSearchController(RunSearchRepository search, TenantRepository tenants) {
        this.search = search;
        this.tenants = tenants;
    }

    @GetMapping("/runs/search")
    public Map<String, Object> search(
            @RequestParam(name = "q") String q,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) List<String> status,
            @RequestParam(name = "has_error", required = false) Boolean hasError,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var tenantId = RequestContext.requireTenantId();
        return run(tenantId, q, agent, status, hasError, from, to, limit, offset);
    }

    @GetMapping("/admin/runs/search")
    public Map<String, Object> adminSearch(
            @RequestParam(name = "tenant") String tenant,
            @RequestParam(name = "q") String q,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) List<String> status,
            @RequestParam(name = "has_error", required = false) Boolean hasError,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var tenantId = tenants.findByName(tenant).map(t -> t.id())
                .orElseThrow(() -> new RuntimeException("unknown tenant: " + tenant));
        return run(tenantId, q, agent, status, hasError, from, to, limit, offset);
    }

    private Map<String, Object> run(UUID tenantId, String q, String agent, List<String> status,
                                    Boolean hasError, Instant from, Instant to, int limit, int offset) {
        if (limit > 100) limit = 100;
        if (limit < 1) limit = 20;
        if (offset < 0) offset = 0;
        var items = search.search(tenantId, q, agent, status, hasError, from, to, limit, offset);
        return Map.of("items", items, "limit", limit, "offset", offset);
    }
}
