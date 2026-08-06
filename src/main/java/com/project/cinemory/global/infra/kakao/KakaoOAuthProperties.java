package com.project.cinemory.global.infra.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 카카오 OIDC 설정.
 *
 * @param issuer             ID 토큰의 {@code iss}와 대조할 값
 * @param jwksUri            공개키 목록 엔드포인트
 * @param allowedAudiences   ID 토큰의 {@code aud}와 대조할 앱 키 <b>목록</b>
 * @param jwkRefreshCooldown JWKS 재조회 최소 간격
 *
 * <p><b>⚠️ {@code allowedAudiences}가 목록인 이유</b> — 카카오는 <b>로그인한 플랫폼에 따라
 * {@code aud} 값이 다르다.</b> 네이티브 앱 SDK로 로그인하면 <b>네이티브 앱 키</b>,
 * 웹에서 하면 REST API 키가 들어온다. 지금은 RN + 네이티브 SDK뿐이라 값이 하나지만,
 * 나중에 웹 로그인을 붙일 때 키를 추가만 하면 되도록 목록으로 둔다.
 *
 * <p><b>비어 있으면 기동을 실패시키는 이유</b> — {@code aud} 검증은 "이 토큰이 <b>우리 앱을 위해</b>
 * 발급된 것인가"를 보는 유일한 장치다. 비워 두면 다른 서비스용으로 발급된 카카오 토큰도
 * 서명·{@code iss}·{@code exp}를 전부 통과한다. 조용히 무력화되느니 기동 시점에 실패하는 편이 낫다
 * ({@code JwtProperties}의 secret 검증과 같은 판단).
 */
@ConfigurationProperties(prefix = "oauth.kakao")
public record KakaoOAuthProperties(
        String issuer,
        String jwksUri,
        List<String> allowedAudiences,
        Duration jwkRefreshCooldown
) {

    public KakaoOAuthProperties {
        requireText(issuer, "oauth.kakao.issuer");
        requireText(jwksUri, "oauth.kakao.jwks-uri");

        if (allowedAudiences == null || allowedAudiences.isEmpty()) {
            throw new IllegalArgumentException(
                    "oauth.kakao.allowed-audiences는 최소 1개 이상이어야 합니다. "
                            + "비워 두면 aud 검증이 무력화됩니다.");
        }
        allowedAudiences.forEach(audience -> requireText(audience, "oauth.kakao.allowed-audiences 항목"));

        if (jwkRefreshCooldown == null || jwkRefreshCooldown.isNegative()) {
            throw new IllegalArgumentException("oauth.kakao.jwk-refresh-cooldown은 0 이상의 Duration이어야 합니다.");
        }
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + "은(는) 필수입니다.");
        }
    }
}
