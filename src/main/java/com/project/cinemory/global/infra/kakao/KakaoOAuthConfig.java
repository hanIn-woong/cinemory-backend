package com.project.cinemory.global.infra.kakao;

import com.project.cinemory.global.infra.oidc.CachingJwkSource;
import com.project.cinemory.global.infra.oidc.JwkSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * 카카오 OIDC 연동 설정. {@code global/infra/kofic}과 같은 구조를 따른다.
 *
 * <p>JWKS 캐시·HTTP 클라이언트는 OIDC 공통({@code global/infra/oidc})을 쓰고, 여기서는 카카오 설정값으로
 * 조립만 한다 (account-integrity D-5-B).
 */
@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties.class)
public class KakaoOAuthConfig {

    @Bean
    public JwkSource kakaoJwkSource(RestClient oidcRestClient, KakaoOAuthProperties properties, Clock clock) {
        return new CachingJwkSource("카카오", properties.jwksUri(), properties.jwkRefreshCooldown(),
                oidcRestClient, clock);
    }
}
