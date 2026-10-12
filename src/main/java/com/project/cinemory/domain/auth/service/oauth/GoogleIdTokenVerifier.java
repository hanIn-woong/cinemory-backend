package com.project.cinemory.domain.auth.service.oauth;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.google.GoogleOAuthProperties;
import com.project.cinemory.global.infra.oidc.JwkSource;
import com.project.cinemory.global.infra.oidc.OidcIdTokenValidator;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;

/**
 * 구글 ID 토큰 검증 (account-integrity D-5-C).
 *
 * <p>서명·{@code iss}·{@code aud}·{@code nonce} 검증은 {@link OidcIdTokenValidator}가 맡고,
 * 이 클래스는 <b>구글 클레임 → {@link OAuthUserInfo} 매핑과 {@code email_verified} 판정만</b> 한다.
 *
 * <p><b>{@code azp}는 검증하지 않는다</b> — Android 토큰은 {@code azp} = Android 클라이언트 ID,
 * {@code aud} = 웹 클라이언트 ID로 온다. 구글의 서버 측 ID 토큰 검증 기준은 {@code aud}다.
 * <b>{@code hd}도 쓰지 않는다</b>(G-4 — 이메일 신뢰는 {@code email_verified}만, 남는 위험은 security-spec L-16).
 */
@Component
public class GoogleIdTokenVerifier implements OAuthIdTokenVerifier {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_EMAIL_VERIFIED = "email_verified";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PICTURE = "picture";

    private static final String DEFAULT_NICKNAME_PREFIX = "구글사용자";
    private static final int NICKNAME_SUFFIX_LENGTH = 6;

    private final OidcIdTokenValidator validator;

    public GoogleIdTokenVerifier(@Qualifier("googleJwkSource") JwkSource jwkSource,
                                 GoogleOAuthProperties properties, Clock clock) {
        this.validator = new OidcIdTokenValidator(
                "구글", jwkSource, Set.copyOf(properties.issuers()), properties.allowedAudiences(), clock);
    }

    @Override
    public OAuthProvider supports() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public OAuthUserInfo verify(String idToken, String expectedNonce) {
        return toUserInfo(validator.validate(idToken, expectedNonce));
    }

    private OAuthUserInfo toUserInfo(Claims claims) {
        // 구글 문서상 계정 식별자는 sub뿐이다 — 이메일은 바뀔 수 있으므로 키로 쓰지 않는다
        String providerId = claims.getSubject();
        if (providerId == null || providerId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }

        // 판정 순서: email 존재 → email_verified. 이메일이 없으면 "인증 안 됨"이 아니라 "제공 안 됨"이 사실이다
        String email = claims.get(CLAIM_EMAIL, String.class);
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED);
        }
        if (!isEmailVerified(claims.get(CLAIM_EMAIL_VERIFIED, Object.class))) {
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_NOT_VERIFIED);
        }

        return new OAuthUserInfo(
                providerId,
                email,
                resolveNickname(claims, providerId),
                claims.get(CLAIM_PICTURE, String.class));
    }

    /**
     * {@code Boolean.TRUE} 또는 문자열 {@code "true"}만 통과한다. 일부 OIDC 제공자는 불리언을 문자열로 보낸다.
     * 누락·{@code false}·그 외 값은 전부 거부한다 — 모르는 형식을 통과시키는 쪽이 위험하다.
     */
    private static boolean isEmailVerified(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    /** 이름이 없어도 가입을 막지 않는다 — 카카오 S-9 E-3과 같은 규칙. */
    private String resolveNickname(Claims claims, String providerId) {
        String name = claims.get(CLAIM_NAME, String.class);
        if (name != null && !name.isBlank()) {
            return name;
        }
        String suffix = providerId.length() <= NICKNAME_SUFFIX_LENGTH
                ? providerId
                : providerId.substring(providerId.length() - NICKNAME_SUFFIX_LENGTH);
        return DEFAULT_NICKNAME_PREFIX + suffix;
    }
}
