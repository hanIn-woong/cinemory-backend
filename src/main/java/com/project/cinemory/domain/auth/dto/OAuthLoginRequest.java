package com.project.cinemory.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param idToken 카카오 SDK가 받아온 ID 토큰
 * @param nonce   {@code POST /api/auth/nonce}로 미리 발급받아 SDK 로그인에 전달했던 값
 *
 * <p>{@code nonce}가 필수인 이유 — 이것이 없으면 "이 ID 토큰이 <b>우리가 방금 시작한 로그인 시도</b>에
 * 대한 응답인가"를 확인할 수 없다. 카카오 ID 토큰의 수명이 약 2시간이라 재전송 창이 짧지 않다.
 */
public record OAuthLoginRequest(

        @NotBlank(message = "idToken은 필수입니다.")
        String idToken,

        @NotBlank(message = "nonce는 필수입니다.")
        String nonce
) {
}
