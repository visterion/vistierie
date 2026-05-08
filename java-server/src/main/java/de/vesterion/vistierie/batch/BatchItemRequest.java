package de.vesterion.vistierie.batch;

import tools.jackson.databind.JsonNode;

public record BatchItemRequest(String custom_id, JsonNode payload) {}
