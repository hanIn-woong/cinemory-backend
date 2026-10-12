package com.project.cinemory.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 소셜 계정 연결 요청 — 필드는 로그인({@code OAuthLoginRequest})과 같다. 연결도 로그인과 같은 검증기
 * (ID 토큰 + nonce)를 거치기 때문이다(account-integrity S-5). 앱은 로그인과 똑같이
 * {@code POST /api/auth/nonce}로 nonce를 받아 SDK 로그인에 넘긴 뒤 여기로 보낸다.
 */
public record SocialLinkRequest(

        @NotBlank(message = "idToken은 필수입니다.")
        String idToken,

        @NotBlank(message = "nonce는 필수입니다.")
        String nonce
) {
}
