package de.vesterion.vistierie.agent.runner;

import tools.jackson.databind.JsonNode;

public record ToolResult(String toolUseId, boolean isError, JsonNode content) {}
