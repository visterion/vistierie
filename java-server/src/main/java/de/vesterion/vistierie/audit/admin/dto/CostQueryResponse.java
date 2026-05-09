package de.vesterion.vistierie.audit.admin.dto;

import java.time.Instant;
import java.util.List;

public record CostQueryResponse(
        Instant from, Instant to,
        String granularity,
        List<String> group_by,
        List<CostBucket> buckets
) {}
