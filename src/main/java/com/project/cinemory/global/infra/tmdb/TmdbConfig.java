package com.project.cinemory.global.infra.tmdb;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbConfig {

    // 타임아웃이 없으면 사실상 무한 대기다 (tmdb-sync 6-8 전제조건 ①).
    // 검색 경로는 이 값을 넘기면 즉시 포기하고 suggestions를 비운다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public RestClient tmdbRestClient(TmdbProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);

        // 토큰 미설정 시 헤더를 아예 붙이지 않는다.
        // "Bearer null"을 보내면 TMDB가 401로 답하고, 원인이 "토큰 미설정"이 아니라
        // "토큰이 틀림"으로 보여서 진단이 한 단계 멀어진다.
        if (properties.isConfigured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken());
        }

        return builder.build();
    }
}
