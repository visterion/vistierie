package de.vesterion.vistierie.runs.dto;

import tools.jackson.databind.JsonNode;

public record CreateRunRequest(
        JsonNode payload,
        String completion_webhook,
        String completion_webhook_token
) {}
