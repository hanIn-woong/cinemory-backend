package com.project.cinemory.domain.auth.service.oauth;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kakao.KakaoOAuthProperties;
import com.project.cinemory.global.infra.oidc.JwkSource;
import com.project.cinemory.global.infra.oidc.OidcIdTokenValidator;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;

/**
 * 카카오 ID 토큰 검증.
 *
 * <p>서명·{@code iss}·{@code aud}·{@code nonce} 검증은 {@link OidcIdTokenValidator}가 맡고(각 검증이 무엇을 막는지도 그쪽에 있다),
 * 이 클래스는 <b>카카오 클레임 → {@link OAuthUserInfo} 매핑만</b> 한다 (account-integrity D-5-B — 상속이 아니라 합성).
 */
@Component
public class KakaoIdTokenVerifier implements OAuthIdTokenVerifier {

    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_PICTURE = "picture";
    private static final String CLAIM_EMAIL = "email";

    private static final String DEFAULT_NICKNAME_PREFIX = "카카오사용자";
    private static final int NICKNAME_SUFFIX_LENGTH = 6;

    private final OidcIdTokenValidator validator;

    public KakaoIdTokenVerifier(@Qualifier("kakaoJwkSource") JwkSource jwkSource,
                                KakaoOAuthProperties properties, Clock clock) {
        // 카카오 설정 키(oauth.kakao.issuer)는 단일 값 그대로 두고 여기서 집합으로 감싼다 — 환경변수·secret 파일 변경 없음
        this.validator = new OidcIdTokenValidator(
                "카카오", jwkSource, Set.of(properties.issuer()), properties.allowedAudiences(), clock);
    }

    @Override
    public OAuthProvider supports() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public OAuthUserInfo verify(String idToken, String expectedNonce) {
        return toUserInfo(validator.validate(idToken, expectedNonce));
    }

    private OAuthUserInfo toUserInfo(Claims claims) {
        String providerId = claims.getSubject();
        if (providerId == null || providerId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }

        String email = claims.get(CLAIM_EMAIL, String.class);
        if (email == null || email.isBlank()) {
            // user.email이 NOT NULL이라 대체값을 만들 수 없다.
            // 플레이스홀더를 쓰면 uk_user_email에 가짜 데이터가 쌓인다 (A-1에서 배제한 방식).
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED);
        }

        return new OAuthUserInfo(
                providerId,
                email,
                resolveNickname(claims, providerId),
                toHttps(claims.get(CLAIM_PICTURE, String.class)));
    }

    /**
     * 닉네임이 없어도 <b>가입을 막지 않는다</b> (S-9 E-3).
     * 이메일과 달리 UNIQUE가 아니고 사용자가 나중에 변경할 수 있어, 대체값이 가짜 데이터로 남지 않는다.
     */
    private String resolveNickname(Claims claims, String providerId) {
        String nickname = claims.get(CLAIM_NICKNAME, String.class);
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        String suffix = providerId.length() <= NICKNAME_SUFFIX_LENGTH
                ? providerId
                : providerId.substring(providerId.length() - NICKNAME_SUFFIX_LENGTH);
        return DEFAULT_NICKNAME_PREFIX + suffix;
    }

    /**
     * 카카오 프로필 이미지 주소를 https로 맞춘다. 카카오는 {@code http://k.kakaocdn.net/…}처럼 <b>http 주소</b>를 준다.
     * Android 릴리스 빌드는 평문 http를 막아 앱에서 사진이 비어 보였다(2026-10-11 Phase 5 preview APK — debug 빌드는 허용이라 개발 중엔 몰랐다).
     * 카카오 CDN은 같은 경로를 https로도 제공한다. 저장 시점에 한 번 바꾸면 이 값을 내보내는 모든 응답(프로필·리뷰·댓글·팔로우)이 함께 고쳐진다.
     */
    static String toHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }
}
