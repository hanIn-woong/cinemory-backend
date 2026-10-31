package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.AssertTrue;
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
        String nickname,

        // 필수 3개(이용약관 동의 · 개인정보처리방침 확인 · 만 14세 이상)를 모두 체크했을 때만 true. 누락도 false라 400이다(ST-7).
        @AssertTrue(message = "이용약관 동의, 개인정보처리방침 확인, 만 14세 이상 확인이 필요합니다.")
        boolean agreeToTerms
) {
}
