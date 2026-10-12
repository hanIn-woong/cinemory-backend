package com.project.cinemory.global.infra.google;

import com.project.cinemory.global.infra.oidc.CachingJwkSource;
import com.project.cinemory.global.infra.oidc.JwkSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * 구글 OIDC 연동 설정. JWKS 캐시·HTTP 클라이언트는 OIDC 공통({@code global/infra/oidc})을 쓰고,
 * 여기서는 구글 설정값으로 조립만 한다 (account-integrity D-5-C).
 */
@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleOAuthConfig {

    @Bean
    public JwkSource googleJwkSource(RestClient oidcRestClient, GoogleOAuthProperties properties, Clock clock) {
        return new CachingJwkSource("구글", properties.jwksUri(), properties.jwkRefreshCooldown(),
                oidcRestClient, clock);
    }
}
