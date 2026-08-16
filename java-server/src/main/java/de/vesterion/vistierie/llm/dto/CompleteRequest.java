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
        String model,
        /** Optional tool definitions, passed straight to the provider. Null keeps the previous
         *  behaviour for every existing caller. */
        List<Map<String, Object>> tools,
        /** Optional tool choice, e.g. {"type":"tool","name":"submit_mailings"}. Forcing a tool is
         *  what stops a model from answering in prose. */
        Object tool_choice
) {}
