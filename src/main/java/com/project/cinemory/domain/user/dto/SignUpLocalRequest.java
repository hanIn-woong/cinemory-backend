package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpLocalRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.MESSAGE)
        String rawPassword,

        // 길이·문자·예약어 규칙은 NicknamePolicy가 서비스에서 판정한다(PR-6) — 여기 길이 한도를 두면 두 곳이 어긋난다
        @NotBlank(message = "닉네임은 필수입니다.")
        String nickname
) {
}
