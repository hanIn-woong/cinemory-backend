package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(

        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.MESSAGE)
        String newPassword
) {
}
