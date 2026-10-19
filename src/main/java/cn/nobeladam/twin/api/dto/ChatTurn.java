package cn.nobeladam.twin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatTurn(
        @NotBlank
        @Pattern(regexp = "user|assistant", message = "role 必须是 user 或 assistant")
        String role,

        @NotBlank
        @Size(max = 2000)
        String content
) {
}
