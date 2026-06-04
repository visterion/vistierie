package de.vesterion.vistierie.llm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record MultiVisionRequest(
        @NotBlank String agent_name,
        @NotBlank String purpose,
        String realm,
        @NotEmpty List<@NotNull @Valid Image> images,
        @NotBlank String prompt,
        Integer max_tokens,
        String model
) {
    public record Image(String type, @NotBlank String media_type, @NotBlank String data) {}
}
