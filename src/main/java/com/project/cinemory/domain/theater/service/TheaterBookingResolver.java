package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.config.TheaterProperties;
import com.project.cinemory.domain.theater.dto.BookingSource;
import com.project.cinemory.domain.theater.entity.Theater;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 예매 링크 판정(cinemap-spec C-8-1) — 서버가 최종 URL을 정해 내려준다.
 * ① 극장별 {@code booking_url}(관리자 입력) → ② 체인 기본 URL(설정) → ③ 없음.
 */
@Component
@RequiredArgsConstructor
public class TheaterBookingResolver {

    private final TheaterProperties properties;

    public record Booking(String url, BookingSource source) {
        static final Booking NONE = new Booking(null, null);
    }

    public Booking resolve(Theater theater) {
        String own = theater.getBookingUrl();
        if (own != null && !own.isBlank()) {
            return new Booking(own, BookingSource.THEATER);
        }
        String chainUrl = theater.getChainName() == null ? null : properties.chainBookingUrls().get(theater.getChainName());
        if (chainUrl != null && !chainUrl.isBlank()) {
            return new Booking(chainUrl, BookingSource.CHAIN);
        }
        return Booking.NONE;
    }
}
