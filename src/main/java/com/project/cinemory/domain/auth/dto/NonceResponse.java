package com.project.cinemory.domain.auth.dto;

/**
 * 소셜 로그인 시작 전 발급받는 일회성 값.
 *
 * @param nonce     카카오 SDK 로그인 요청 시 그대로 전달하고, 이후 {@code /api/auth/oauth/{provider}}에도 함께 보낸다
 * @param expiresIn 유효 기간(초). 이 안에 로그인을 마쳐야 한다
 *
 * <p>{@code TokenResponse}와 마찬가지로 절대 시각이 아니라 <b>남은 초</b>로 준다 —
 * 기기 시계 오차에 영향을 받지 않는다.
 */
public record NonceResponse(String nonce, long expiresIn) {
}
