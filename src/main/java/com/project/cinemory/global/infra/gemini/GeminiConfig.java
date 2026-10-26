package com.project.cinemory.global.infra.gemini;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfig {

    // 사용자 대면 질의 경로 — 넘기면 즉시 포기하고 규칙 기반 추천으로 대체한다(M-1 격리 인터페이스).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration QUERY_READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public RestClient geminiQueryRestClient(GeminiProperties properties) {
        return build(properties, QUERY_READ_TIMEOUT);
    }

    @Bean
    public RestClient geminiBatchRestClient(GeminiProperties properties) {
        return build(properties, properties.batchReadTimeout());
    }

    private RestClient build(GeminiProperties properties, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(readTimeout);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);

        // 미설정 시 헤더를 붙이지 않는다 — TMDB와 같은 이유(진단: "키 없음"과 "키 틀림"을 구분).
        if (properties.isConfigured()) {
            builder.defaultHeader("x-goog-api-key", properties.apiKey());
        }
        return builder.build();
    }
}
