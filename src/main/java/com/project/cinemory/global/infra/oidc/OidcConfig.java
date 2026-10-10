package com.project.cinemory.global.infra.oidc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * OIDC 제공자 공용 HTTP 클라이언트 (account-integrity D-5-B). 제공자별 {@link CachingJwkSource}가 함께 쓴다.
 *
 * <p>{@code baseUrl}을 두지 않는 이유 — JWKS URI를 제공자 설정값에서 <b>절대 URL로</b> 받기 때문이다.
 */
@Configuration
public class OidcConfig {

    // 타임아웃이 없으면 사실상 무한 대기다. JWKS 조회는 kid 미스 때 로그인 요청 스레드 안에서 일어나므로
    // 제공자가 응답하지 않으면 요청이 붙잡힌다. 조회 실패는 CachingJwkSource가 삼켜 캐시된 키로 계속 동작한다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public RestClient oidcRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
