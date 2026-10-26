package com.project.cinemory.global.infra.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Gemini 임베딩 설정.
 *
 * <p>{@code apiKey}만 비밀이다 — 로컬은 {@code config/application-secret.yml}, 운영은 {@code GEMINI_API_KEY}.
 * 키는 헤더({@code x-goog-api-key})로만 보낸다. 쿼리 스트링에 넣으면 접근 로그·예외 메시지에 남는다.
 *
 * @param batchReadTimeout 문서 배치(최대 100건) 읽기 타임아웃. 질의 경로의 3초는 사용자 대면이라 짧게 잡았지만
 *                         배치는 응답이 커서 같은 값을 쓰면 정상 요청도 끊는다.
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(String apiKey, String baseUrl, String model, Duration batchReadTimeout) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
