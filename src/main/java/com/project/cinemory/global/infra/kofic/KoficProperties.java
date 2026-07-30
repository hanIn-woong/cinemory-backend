package com.project.cinemory.global.infra.kofic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KOFIC(영화진흥위원회) KOBIS Open API 설정.
 * API 키는 저장소에 커밋하지 않고 {@code application-secret.yml}에 둔다.
 */
@ConfigurationProperties(prefix = "kofic")
public record KoficProperties(String baseUrl, String apiKey) {

    /** 키가 비어 있으면 배치를 건너뛴다 (미설정 환경에서 애플리케이션 기동을 막지 않기 위함) */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
