package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

public record NicknameChangeRequest(

        // 길이·문자·예약어 규칙은 NicknamePolicy가 서비스에서 판정한다(PR-6)
        @NotBlank(message = "닉네임은 필수입니다.")
        String nickname
) {
}
