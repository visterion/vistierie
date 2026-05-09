package de.vesterion.vistierie.audit.admin;

import de.vesterion.vistierie.audit.admin.dto.CostBucket;
import de.vesterion.vistierie.audit.admin.dto.CostGroup;
import de.vesterion.vistierie.audit.admin.dto.CostQueryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/admin/cost")
public class AdminCostController {

    private static final int MAX_BUCKETS = 10_000;

    private final CostAggregationRepository repo;

    public AdminCostController(CostAggregationRepository repo) { this.repo = repo; }

    @GetMapping
    public CostQueryResponse query(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "hour") String granularity,
            @RequestParam(name = "group_by", required = false) String groupByCsv,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String realm,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) List<String> status) {

        if (from == null) from = Instant.now().minus(7, ChronoUnit.DAYS);
        if (to == null)   to   = Instant.now();
        var groupBy = groupByCsv == null || groupByCsv.isBlank()
                ? List.<String>of()
                : Arrays.stream(groupByCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        var q = new CostAggregationRepository.Query(
                from, to, granularity, groupBy,
                tenant, realm, purpose, provider, model, endpoint, status);

        List<CostAggregationRepository.AggregatedRow> rows;
        try {
            rows = repo.query(q);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (rows.size() > MAX_BUCKETS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "response_too_large: " + rows.size() + " rows. Narrow the time range or reduce group_by.");
        }

        Map<Instant, List<CostGroup>> byBucket = new LinkedHashMap<>();
        for (var r : rows) {
            var key = r.bucket();
            byBucket.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new CostGroup(
                            r.groupValues(),
                            r.calls(),
                            r.inputTokens(),
                            r.outputTokens(),
                            r.cacheCreationInputTokens(),
                            r.cacheReadInputTokens(),
                            r.costMicros(),
                            r.costMicros() / 1_000_000.0));
        }
        var buckets = byBucket.entrySet().stream()
                .map(e -> new CostBucket(e.getKey(), e.getValue()))
                .toList();

        return new CostQueryResponse(from, to, granularity, groupBy, buckets);
    }
}
