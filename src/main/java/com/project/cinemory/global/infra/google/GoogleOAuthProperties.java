package com.project.cinemory.global.infra.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 구글 OIDC 설정 (account-integrity D-5-C).
 *
 * @param issuers            ID 토큰의 {@code iss}와 대조할 값 <b>목록</b>
 * @param jwksUri            공개키 목록 엔드포인트
 * @param allowedAudiences   ID 토큰의 {@code aud}와 대조할 클라이언트 ID <b>목록</b>
 * @param jwkRefreshCooldown JWKS 재조회 최소 간격
 *
 * <p><b>⚠️ {@code issuers}가 목록인 이유</b> — 구글은 {@code https://accounts.google.com}과
 * {@code accounts.google.com} <b>두 형식을 모두 발급한다</b>(공식 문서). 하나만 넣으면 일부 토큰이 이유 없이 거부된다.
 *
 * <p><b>{@code allowedAudiences}는 웹 클라이언트 ID다</b> — Android 앱이 받은 토큰도 {@code aud}는 웹 클라이언트 ID이고
 * Android 클라이언트 ID는 {@code azp}에 들어간다(D-5-C 클레임 매핑). iOS를 붙이면 iOS 클라이언트 ID를 추가만 한다(D-5-H).
 *
 * <p><b>비어 있으면 기동을 실패시키는 이유</b>는 {@code KakaoOAuthProperties}와 같다 — {@code aud} 검증은
 * "이 토큰이 <b>우리 앱을 위해</b> 발급된 것인가"를 보는 유일한 장치라, 비워 두면 다른 서비스용으로 발급된 구글 토큰도
 * 서명·{@code iss}·{@code exp}를 전부 통과한다. {@code issuers}가 비면 모든 토큰이 거부되어 조용히 고장 난다.
 */
@ConfigurationProperties(prefix = "oauth.google")
public record GoogleOAuthProperties(
        List<String> issuers,
        String jwksUri,
        List<String> allowedAudiences,
        Duration jwkRefreshCooldown
) {

    public GoogleOAuthProperties {
        requireNonEmpty(issuers, "oauth.google.issuers", "비어 있으면 모든 토큰이 거부됩니다.");
        requireText(jwksUri, "oauth.google.jwks-uri");
        requireNonEmpty(allowedAudiences, "oauth.google.allowed-audiences", "비워 두면 aud 검증이 무력화됩니다.");

        if (jwkRefreshCooldown == null || jwkRefreshCooldown.isNegative()) {
            throw new IllegalArgumentException("oauth.google.jwk-refresh-cooldown은 0 이상의 Duration이어야 합니다.");
        }
    }

    private static void requireNonEmpty(List<String> values, String key, String reason) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(key + "는 최소 1개 이상이어야 합니다. " + reason);
        }
        values.forEach(value -> requireText(value, key + " 항목"));
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + "은(는) 필수입니다.");
        }
    }
}
