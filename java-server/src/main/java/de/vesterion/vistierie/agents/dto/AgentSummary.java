package de.vesterion.vistierie.agents.dto;

import java.time.Instant;

public record AgentSummary(String id, String name, int version, boolean paused, Instant updated_at) {}
