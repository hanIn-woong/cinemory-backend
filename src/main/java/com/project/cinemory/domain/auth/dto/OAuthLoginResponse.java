package com.project.cinemory.domain.auth.dto;

/**
 * 소셜 로그인 응답 — {@code POST /api/auth/oauth/{provider}} 전용. {@code TokenResponse}에 필드를 더하지 않고 따로 둔 이유:
 * 로그인·재발급이 같이 쓰는 {@code TokenResponse}를 바꾸면 앱의 재발급 single-flight 경로가 흔들린다(profile-spec 3-3).
 *
 * @param newUser 이번 요청으로 가입이 일어났는가 — 앱이 가입 직후 닉네임 설정 화면을 한 번 띄운다
 */
public record OAuthLoginResponse(String accessToken, String refreshToken, long accessTokenExpiresIn, boolean newUser) {
}
