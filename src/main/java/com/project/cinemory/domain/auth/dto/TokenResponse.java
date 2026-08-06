package com.project.cinemory.domain.auth.dto;

/**
 * @param accessToken           API 호출 시 {@code Authorization: Bearer <token>}으로 보낸다
 * @param refreshToken          재발급·로그아웃에 쓰는 불투명 값(JWT 아님). DB에는 이 값의 SHA-256 해시만 저장된다
 * @param accessTokenExpiresIn  Access Token 유효 기간(초)
 *
 * <p>{@code accessTokenExpiresIn}을 내려주는 이유는 클라이언트가 JWT 페이로드를 직접 디코드해
 * {@code exp}를 꺼내지 않아도 재발급 시점을 알 수 있게 하기 위함이다. 절대 시각이 아니라
 * <b>남은 초</b>로 주는 편이 기기 시계 오차에 영향을 받지 않는다.
 *
 * <p>사용자 정보는 담지 않는다 — 인증 응답이 User DTO에 결합되면 프로필 스키마가 바뀔 때마다
 * 인증 계약이 함께 흔들린다.
 */
public record TokenResponse(String accessToken, String refreshToken, long accessTokenExpiresIn) {
}
