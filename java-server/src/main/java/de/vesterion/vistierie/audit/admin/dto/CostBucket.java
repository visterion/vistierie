package de.vesterion.vistierie.audit.admin.dto;

import java.time.Instant;
import java.util.List;

public record CostBucket(Instant ts, List<CostGroup> groups) {}
