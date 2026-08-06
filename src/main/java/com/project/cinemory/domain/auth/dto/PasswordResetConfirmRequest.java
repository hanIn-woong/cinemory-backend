package com.project.cinemory.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 재설정 확정. 비밀번호 정책은 {@code SignUpLocalRequest}와 <b>같은 8~64자</b>를 쓴다 —
 * 가입과 재설정의 규칙이 다르면 사용자가 혼란스럽고, 재설정 쪽이 느슨하면
 * 가입 정책이 우회된다.
 */
public record PasswordResetConfirmRequest(

        @NotBlank(message = "토큰은 필수입니다.")
        String token,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
        String newPassword
) {
}
