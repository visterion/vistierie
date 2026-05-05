package de.vesterion.vistierie.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VisionRequest(
        @NotBlank String purpose,
        String realm,
        @NotNull Image image,
        @NotBlank String prompt,
        Integer max_tokens,
        String model
) {
    public record Image(String type, String media_type, String data) {}
}
