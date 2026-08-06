package com.project.cinemory.domain.auth.entity;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;

import java.util.Locale;

/**
 * 지원하는 소셜 로그인 제공자. {@code user.provider}에 이름 그대로 저장된다.
 *
 * <p><b>{@code KAKAO}만 정의한다</b> (S-9 A-1). 구현체가 없는 provider 값을 미리 넣어두면
 * "지원하는 것처럼 보이지만 런타임에 터지는" 상태가 된다. 구글·애플은 검증기 구현체가 생기는
 * 시점에 값을 함께 추가한다.
 */
public enum OAuthProvider {

    KAKAO;

    /**
     * 경로 변수({@code /api/auth/oauth/{provider}})를 enum으로 변환한다.
     * 대소문자는 무시한다 — 클라이언트가 {@code kakao}로 보내는 편이 자연스럽다.
     *
     * @throws BusinessException {@code UNSUPPORTED_OAUTH_PROVIDER}(400)
     */
    public static OAuthProvider from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
    }
}
