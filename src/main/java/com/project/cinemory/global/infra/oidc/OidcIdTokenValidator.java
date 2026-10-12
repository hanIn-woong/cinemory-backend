package com.project.cinemory.global.infra.oidc;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import lombok.extern.slf4j.Slf4j;

import java.security.Key;
import java.time.Clock;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * OIDC ID 토큰의 표준 검증 — 제공자 검증기가 공통으로 쓴다 (account-integrity D-5-B, 2026-10-11 {@code KakaoIdTokenVerifier}에서 분리).
 *
 * <p><b>검증 항목 4종</b> — 서명 / {@code iss} / {@code aud} / {@code nonce}
 * ({@code exp}는 jjwt가 파싱 단계에서 처리한다). 하나라도 빼면 그만큼 구멍이 생긴다.
 *
 * <ul>
 *   <li><b>서명</b> — 이 토큰이 진짜 그 제공자가 발급한 것인지. 없으면 페이로드를 마음대로 써서
 *       아무 계정으로나 로그인할 수 있다</li>
 *   <li><b>{@code iss}</b> — 다른 OIDC 제공자의 토큰을 이 제공자 토큰인 척 넣는 것을 막는다</li>
 *   <li><b>{@code aud}</b> — <b>다른 서비스용으로 발급된 같은 제공자의 토큰</b>을 막는다.
 *       같은 제공자가 발급했으므로 서명 검증만으로는 통과한다</li>
 *   <li><b>{@code nonce}</b> — 위 셋을 모두 통과한 <b>유효한 토큰의 재전송</b>을 막는다</li>
 * </ul>
 *
 * <p><b>빈이 아닌 상태 없는 일반 클래스이고, 상속이 아니라 합성으로 쓴다.</b> 제공자 검증기는 생성자에서 이 클래스를 만들고
 * {@link #validate}를 호출한 뒤 <b>클레임 → 사용자 정보 매핑만</b> 한다. 제공자 간 차이는 매핑뿐이고 검증 순서는 바뀔 여지가 없어야 한다 —
 * 템플릿 메서드 상속으로 만들면 하위 클래스가 검증 단계를 오버라이드해 빠뜨릴 수 있다.
 *
 * <p><b>확장 지점(기록만)</b> — Apple은 토큰에 nonce의 SHA-256 해시를 넣는다. Apple을 추가할 때 생성자에
 * {@code UnaryOperator<String> nonceTransform}(기본값 identity)을 더하면 된다. 쓰는 곳 없는 확장이라 지금은 넣지 않는다.
 */
@Slf4j
public class OidcIdTokenValidator {

    private static final String CLAIM_NONCE = "nonce";

    /** 제공자 서버와 우리 서버의 시계 오차 허용치. 없으면 방금 발급된 토큰이 튕길 수 있다. */
    private static final long CLOCK_SKEW_SECONDS = 30L;

    /** 로그에만 쓴다 — 어느 제공자의 토큰인지 구분하기 위해서다. */
    private final String providerName;
    private final JwkSource jwkSource;
    /** 구글처럼 같은 발급자를 두 형식으로 쓰는 제공자가 있어 집합으로 받는다. */
    private final Set<String> issuers;
    private final List<String> allowedAudiences;
    private final Clock clock;

    public OidcIdTokenValidator(String providerName, JwkSource jwkSource, Set<String> issuers,
                                Collection<String> allowedAudiences, Clock clock) {
        this.providerName = providerName;
        this.jwkSource = jwkSource;
        this.issuers = Set.copyOf(issuers);
        this.allowedAudiences = List.copyOf(allowedAudiences);
        this.clock = clock;
    }

    /**
     * 서명·만료·{@code iss}·{@code aud}·{@code nonce}를 이 순서로 검증하고 클레임을 돌려준다.
     *
     * @throws BusinessException {@code INVALID_OAUTH_TOKEN} — nonce 외의 검증 실패 /
     *                           {@code INVALID_NONCE} — nonce 불일치
     */
    public Claims validate(String idToken, String expectedNonce) {
        Claims claims = parseAndVerifySignature(idToken);

        validateIssuer(claims);
        validateAudience(claims);
        validateNonce(claims, expectedNonce);

        return claims;
    }

    /**
     * 서명과 만료를 검증하고 클레임을 꺼낸다.
     *
     * <p>토큰 헤더의 {@code kid}로 공개키를 골라야 하므로 고정 키가 아니라
     * {@link Locator}를 쓴다. 제공자는 키를 여러 개 운영하고 교체(롤오버)한다.
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
            // 소셜 로그인을 처음부터 다시 해야 하므로 구분할 실익이 없다.
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
    }

    private void validateIssuer(Claims claims) {
        if (!issuers.contains(claims.getIssuer())) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
    }

    /**
     * {@code aud}는 OIDC 표준상 문자열 또는 배열이다. 단일 값이든 배열이든
     * 허용 목록과 교집합이 있으면 통과시킨다.
     *
     * <p>허용 목록인 이유 — 제공자에 따라 <b>로그인 플랫폼마다 값이 다르다</b>
     * (예: 카카오는 네이티브 앱 SDK → 네이티브 앱 키 / 웹 → REST API 키).
     */
    private void validateAudience(Claims claims) {
        Set<String> audiences = claims.getAudience();
        if (audiences == null || audiences.stream().noneMatch(allowedAudiences::contains)) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
    }

    /**
     * nonce 실패는 {@code INVALID_OAUTH_TOKEN}이 아니라 {@code INVALID_NONCE}다 — 클라이언트 분기가 다르다.
     *
     * <p><b>서버 로그를 남기는 이유(security-spec L-14).</b> 클라이언트는 "캐시에 없음"과 "토큰 속 값 불일치"를
     * 같은 {@code INVALID_NONCE}로 받는다. 서버 로그에서도 갈리지 않으면 실토큰 디버깅이 막힌다(2026-08-27).
     * 값은 남기지 않는다.
     */
    private void validateNonce(Claims claims, String expectedNonce) {
        String actual = claims.get(CLAIM_NONCE, String.class);
        if (actual == null || !actual.equals(expectedNonce)) {
            log.warn("{} ID 토큰 nonce 불일치", providerName);
            throw new BusinessException(ErrorCode.INVALID_NONCE);
        }
    }
}
