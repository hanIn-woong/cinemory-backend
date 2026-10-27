package com.project.cinemory.domain.theater.dto;

import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.service.TheaterBookingResolver;

import java.math.BigDecimal;

/**
 * 주변 극장 목록 항목.
 *
 * <p>{@code distanceMeters}는 조회 기준점에 따라 달라지는 계산값이므로
 * {@code from(Entity)} 단일 인자 규칙의 예외로 두 번째 파라미터를 받는다.
 * (4-6 {@code FollowUserResponse.from(User, boolean)}과 동일한 패턴). 예매 링크는 설정이 섞여 판정되므로 서비스가 만든
 * {@link TheaterBookingResolver.Booking}을 받는다(C-8-4).
 */
public record TheaterResponse(
        Long theaterId,
        String name,
        String chainName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer screenCount,
        Integer seatCount,
        long distanceMeters,
        String bookingUrl,             // C-8-1 순서로 판정한 최종 URL, 없으면 null
        BookingSource bookingSource    // THEATER | CHAIN | null
) {

    public static TheaterResponse from(Theater theater, double distanceMeters, TheaterBookingResolver.Booking booking) {
        return new TheaterResponse(
                theater.getId(),
                theater.getName(),
                theater.getChainName(),
                theater.getAddress(),
                theater.getLatitude(),
                theater.getLongitude(),
                theater.getScreenCount(),
                theater.getSeatCount(),
                Math.round(distanceMeters),
                booking.url(),
                booking.source()
        );
    }
}
