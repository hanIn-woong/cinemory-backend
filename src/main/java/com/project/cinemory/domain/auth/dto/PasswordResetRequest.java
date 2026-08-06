package com.project.cinemory.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 메일 요청. 형식 검증만 하고, <b>계정 존재 여부는 응답에 드러내지 않는다</b> —
 * 어떤 경우든 200이다(S-10 ①).
 */
public record PasswordResetRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
        String email
) {
}
