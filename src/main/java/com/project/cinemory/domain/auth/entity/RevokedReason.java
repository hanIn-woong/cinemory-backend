package com.project.cinemory.domain.auth.entity;

/**
 * 리프레시 토큰의 폐기 사유. {@code refresh_token.revoked_reason}에 매핑된다 (v10 신규).
 *
 * <p><b>이 enum이 없으면 로그아웃이 유예 창 시간만큼 무효화된다.</b> 유예 판정이
 * {@code revokedAt}만 본다면 회전으로 폐기된 토큰과 로그아웃으로 폐기된 토큰을 구분하지 못해,
 * 로그아웃 직후 같은 토큰으로 재발급하면 세션이 되살아난다. v10을 연 직접적인 이유다.
 *
 * <p>{@code revokedAt}과 항상 함께 채워진다 — 이 불변식은 DB의
 * {@code chk_refresh_token_revocation} CHECK 제약으로도 고정돼 있다.
 */
public enum RevokedReason {

    /** 재발급 회전({@code AuthService.reissue}). <b>유예 창의 대상이 되는 유일한 값.</b> */
    ROTATED,

    /** 로그아웃({@code AuthService.logout}). */
    LOGOUT,

    /** 재사용 감지에 따른 전체 세션 폐기. */
    REUSE_DETECTED,

    /** 비밀번호 변경(S-H) / 재설정(S-J)에 따른 전체 세션 폐기. */
    PASSWORD_CHANGED
}
