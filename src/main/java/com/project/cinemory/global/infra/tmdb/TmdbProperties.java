package com.project.cinemory.global.infra.tmdb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TMDB API 설정.
 *
 * <p>인증은 v4 Read Access Token의 Bearer 방식이다. {@code api_key} 쿼리 파라미터 방식도
 * 동작하지만 공식 문서가 신규 연동에 Bearer를 권장하고, 무엇보다 URL과 접근 로그에
 * 키가 남지 않는다.
 *
 * <p>토큰은 저장소에 커밋하지 않고 {@code application-secret.yml}에 둔다
 * ({@code KoficProperties}와 동일한 구조).
 */
@ConfigurationProperties(prefix = "tmdb")
public record TmdbProperties(String baseUrl, String accessToken) {

    /**
     * 토큰이 비어 있으면 TMDB 연동 작업을 건너뛴다.
     *
     * <p>기동 자체를 막지 않는 이유는 KOFIC과 같다 — 토큰이 없는 개발 환경에서도
     * 나머지 기능은 띄울 수 있어야 한다.
     */
    public boolean isConfigured() {
        return accessToken != null && !accessToken.isBlank();
    }
}
