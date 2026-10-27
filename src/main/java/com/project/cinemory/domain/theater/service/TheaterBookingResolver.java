package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.config.TheaterProperties;
import com.project.cinemory.domain.theater.dto.BookingSource;
import com.project.cinemory.domain.theater.entity.Theater;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 예매 링크 판정(cinemap-spec C-8-1) — 서버가 최종 URL을 정해 내려준다.
 * ① 극장별 {@code booking_url}(관리자 입력) → ② 체인 기본 URL(설정) → ③ 없음.
 */
@Component
@RequiredArgsConstructor
public class TheaterBookingResolver {

    /**
     * 체인명(DB·{@code TheaterNormalizer}의 값) → 설정 키. 설정 키를 한글로 두면 Spring Boot가 프로퍼티 이름에서 영문·숫자 외 문자를
     * 걸러 {@code Map} 바인딩에서 사라진다(운영 첫 실행에서 롯데시네마·씨네Q 링크가 비었다). 그래서 설정 키는 ASCII로 두고 여기서 잇는다.
     */
    static final Map<String, String> CHAIN_CONFIG_KEYS = Map.of(
            "CGV", "cgv",
            "롯데시네마", "lotte-cinema",
            "메가박스", "megabox",
            "씨네Q", "cine-q");

    private final TheaterProperties properties;

    public record Booking(String url, BookingSource source) {
        static final Booking NONE = new Booking(null, null);
    }

    public Booking resolve(Theater theater) {
        String own = theater.getBookingUrl();
        if (own != null && !own.isBlank()) {
            return new Booking(own, BookingSource.THEATER);
        }
        String configKey = theater.getChainName() == null ? null : CHAIN_CONFIG_KEYS.get(theater.getChainName());
        String chainUrl = configKey == null ? null : properties.chainBookingUrls().get(configKey);
        if (chainUrl != null && !chainUrl.isBlank()) {
            return new Booking(chainUrl, BookingSource.CHAIN);
        }
        return Booking.NONE;
    }
}
