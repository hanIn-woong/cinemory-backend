package com.project.cinemory.global.infra.publicdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털 설정(cinemap-spec C-4-1).
 *
 * <p>{@code serviceKey}는 <b>Decoding 키</b>를 넣는다 — 인코딩은 HTTP 클라이언트가 한다(Encoding 키를 넣으면 이중 인코딩).
 * {@code theaterPath}는 영화상영관 조회서비스의 경로다.
 */
@ConfigurationProperties(prefix = "publicdata")
public record PublicDataProperties(String baseUrl, String theaterPath, String serviceKey) {

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }
}
