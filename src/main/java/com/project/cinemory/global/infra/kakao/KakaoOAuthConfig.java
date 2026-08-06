package com.project.cinemory.global.infra.kakao;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 카카오 OIDC 연동 설정. {@code global/infra/kofic}과 같은 구조를 따른다.
 *
 * <p>{@code baseUrl}을 두지 않는 이유 — JWKS URI를 설정값에서 <b>절대 URL로</b> 받기 때문이다.
 * 카카오 엔드포인트가 여럿으로 늘어나면 그때 baseUrl 방식으로 바꾼다.
 */
@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties.class)
public class KakaoOAuthConfig {

    @Bean
    public RestClient kakaoRestClient() {
        return RestClient.create();
    }
}
