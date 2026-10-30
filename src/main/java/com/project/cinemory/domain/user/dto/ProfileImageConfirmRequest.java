package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfileImageConfirmRequest(

        @NotBlank(message = "키는 필수입니다.")
        String key
) {
}
