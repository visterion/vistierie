package de.vesterion.vistierie.runs.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record RunEventDto(long id, Instant ts, String level, String type, JsonNode payload) {}
