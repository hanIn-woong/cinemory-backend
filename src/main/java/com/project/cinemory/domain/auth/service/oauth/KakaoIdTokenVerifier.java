package com.project.cinemory.domain.auth.service.oauth;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kakao.KakaoJwkSource;
import com.project.cinemory.global.infra.kakao.KakaoOAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Clock;
import java.util.Date;
import java.util.Set;

/**
 * 카카오 ID 토큰 검증.
 *
 * <p><b>검증 항목 4종</b> — 서명 / {@code iss} / {@code aud} / {@code nonce}
 * ({@code exp}는 jjwt가 파싱 단계에서 처리한다). 하나라도 빼면 그만큼 구멍이 생긴다.
 *
 * <ul>
 *   <li><b>서명</b> — 이 토큰이 진짜 카카오가 발급한 것인지. 없으면 페이로드를 마음대로 써서
 *       아무 계정으로나 로그인할 수 있다</li>
 *   <li><b>{@code iss}</b> — 다른 OIDC 제공자의 토큰을 카카오 토큰인 척 넣는 것을 막는다</li>
 *   <li><b>{@code aud}</b> — <b>다른 서비스용으로 발급된 카카오 토큰</b>을 막는다.
 *       같은 카카오가 발급했으므로 서명 검증만으로는 통과한다</li>
 *   <li><b>{@code nonce}</b> — 위 셋을 모두 통과한 <b>유효한 토큰의 재전송</b>을 막는다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class KakaoIdTokenVerifier implements OAuthIdTokenVerifier {

    private static final String CLAIM_NONCE = "nonce";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_PICTURE = "picture";
    private static final String CLAIM_EMAIL = "email";

    /** 카카오 서버와 우리 서버의 시계 오차 허용치. 없으면 방금 발급된 토큰이 튕길 수 있다. */
    private static final long CLOCK_SKEW_SECONDS = 30L;

    private static final String DEFAULT_NICKNAME_PREFIX = "카카오사용자";
    private static final int NICKNAME_SUFFIX_LENGTH = 6;

    private final KakaoJwkSource jwkSource;
    private final KakaoOAuthProperties properties;
    private final Clock clock;

    @Override
    public OAuthProvider supports() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public OAuthUserInfo verify(String idToken, String expectedNonce) {
        Claims claims = parseAndVerifySignature(idToken);

        validateIssuer(claims);
        validateAudience(claims);
        validateNonce(claims, expectedNonce);

        return toUserInfo(claims);
    }

    /**
     * 서명과 만료를 검증하고 클레임을 꺼낸다.
     *
     * <p>토큰 헤더의 {@code kid}로 공개키를 골라야 하므로 고정 키가 아니라
     * {@link Locator}를 쓴다. 카카오는 키를 여러 개 운영하고 교체(롤오버)한다.
     */
    private Claims parseAndVerifySignature(String idToken) {
        Locator<Key> keyLocator = header -> {
            if (header instanceof JwsHeader jwsHeader) {
                return jwkSource.findByKid(jwsHeader.getKeyId());
            }
            // 서명이 없는 토큰(JWT unsecured)은 받지 않는다
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        };

        try {
            return Jwts.parser()
                    .keyLocator(keyLocator)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    // 시간 소스를 주입해 테스트에서 만료 경계를 고정할 수 있게 한다
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();

        } catch (BusinessException e) {
            // keyLocator가 던진 것 — 이미 적절한 ErrorCode를 담고 있다
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            // 만료(ExpiredJwtException)도 여기로 수렴시킨다.
            // 우리 Access Token의 TOKEN_EXPIRED와 달리 클라이언트가 "재발급"할 수 있는 대상이 아니라,
            // 카카오 로그인을 처음부터 다시 해야 하므로 구분할 실익이 없다.
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
    }

    private void validateIssuer(Claims claims) {
        if (!properties.issuer().equals(claims.getIssuer())) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
    }

    /**
     * {@code aud}는 OIDC 표준상 문자열 또는 배열이다. 카카오는 단일 값을 보내지만
     * 배열로 와도 허용 목록과 교집합이 있으면 통과시킨다.
     *
     * <p>허용 목록인 이유 — 카카오는 <b>로그인 플랫폼에 따라 값이 다르다</b>
     * (네이티브 앱 SDK → 네이티브 앱 키 / 웹 → REST API 키).
     */
    private void validateAudience(Claims claims) {
        Set<String> audiences = claims.getAudience();
        if (audiences == null || audiences.stream().noneMatch(properties.allowedAudiences()::contains)) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
    }

    /** nonce 실패는 {@code INVALID_OAUTH_TOKEN}이 아니라 {@code INVALID_NONCE}다 — 클라이언트 분기가 다르다. */
    private void validateNonce(Claims claims, String expectedNonce) {
        String actual = claims.get(CLAIM_NONCE, String.class);
        if (actual == null || !actual.equals(expectedNonce)) {
            throw new BusinessException(ErrorCode.INVALID_NONCE);
        }
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
                claims.get(CLAIM_PICTURE, String.class));
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
}
