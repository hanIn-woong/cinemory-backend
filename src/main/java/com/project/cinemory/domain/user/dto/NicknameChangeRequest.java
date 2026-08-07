package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NicknameChangeRequest(

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 30, message = "닉네임은 30자를 넘을 수 없습니다.")
        String nickname
) {
}
