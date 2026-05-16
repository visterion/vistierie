package de.vesterion.vistierie.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record CompleteRequest(
        @NotBlank String agent_name,
        @NotBlank String purpose,
        String realm,
        String system,
        @NotNull List<Map<String, Object>> messages,
        Integer max_tokens,
        Double temperature,
        String model
) {}
