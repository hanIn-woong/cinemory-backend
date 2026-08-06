package com.project.cinemory.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 형식 검증은 {@code @NotBlank}까지만 둔다. 이메일 형식이나 비밀번호 길이를 여기서 막으면
 * <b>"이 이메일은 우리 서비스 형식이 아니다"</b> 같은 정보가 새고, 회원가입 규칙이 바뀌었을 때
 * 기존 사용자가 로그인하지 못하게 된다. 자격증명 판정은 전부 {@code INVALID_CREDENTIALS} 하나로 수렴시킨다.
 */
public record LoginRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
