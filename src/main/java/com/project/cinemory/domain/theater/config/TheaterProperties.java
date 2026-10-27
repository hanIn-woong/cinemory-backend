package com.project.cinemory.domain.theater.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 극장 설정 중 구조가 있는 값(cinemap-spec C-8-1). 단순 숫자 값({@code default-radius-meters} 등)은 기존처럼 서비스가 {@code @Value}로 읽는다.
 *
 * @param chainBookingUrls 설정 키(ASCII: cgv · lotte-cinema · megabox · cine-q) → 예매 페이지 https URL.
 *                         체인명(한글)과의 연결은 {@code TheaterBookingResolver.CHAIN_CONFIG_KEYS}
 */
@ConfigurationProperties(prefix = "cinemory.theater")
public record TheaterProperties(Map<String, String> chainBookingUrls) {

    public TheaterProperties {
        chainBookingUrls = chainBookingUrls == null ? Map.of() : Map.copyOf(chainBookingUrls);
    }
}
