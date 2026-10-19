package cn.nobeladam.twin.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "requestId 只能包含字母、数字、下划线和短横线")
        String requestId,

        @NotBlank
        @Size(max = 500)
        String message,

        @Valid
        @Size(max = 8)
        List<ChatTurn> history
) {
}
