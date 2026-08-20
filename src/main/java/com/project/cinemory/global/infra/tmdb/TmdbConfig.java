package com.project.cinemory.global.infra.tmdb;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbConfig {

    @Bean
    public RestClient tmdbRestClient(TmdbProperties properties) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl());

        // 토큰 미설정 시 헤더를 아예 붙이지 않는다.
        // "Bearer null"을 보내면 TMDB가 401로 답하고, 원인이 "토큰 미설정"이 아니라
        // "토큰이 틀림"으로 보여서 진단이 한 단계 멀어진다.
        if (properties.isConfigured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken());
        }

        return builder.build();
    }
}
