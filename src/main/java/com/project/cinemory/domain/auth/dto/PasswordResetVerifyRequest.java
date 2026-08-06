package com.project.cinemory.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 딥링크로 받은 토큰의 사용 가능 여부 확인 (S-9 F-3). <b>토큰을 소비하지 않는다.</b>
 * 이 엔드포인트가 없으면 사용자가 새 비밀번호를 다 입력한 뒤에야 만료를 알게 된다.
 */
public record PasswordResetVerifyRequest(

        @NotBlank(message = "토큰은 필수입니다.")
        String token
) {
}
