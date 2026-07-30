package com.project.cinemory.domain.theater.dto;

import com.project.cinemory.domain.theater.entity.Theater;

import java.math.BigDecimal;

/**
 * 주변 극장 목록 항목.
 *
 * <p>{@code distanceMeters}는 조회 기준점에 따라 달라지는 계산값이므로
 * {@code from(Entity)} 단일 인자 규칙의 예외로 두 번째 파라미터를 받는다.
 * (4-6 {@code FollowUserResponse.from(User, boolean)}과 동일한 패턴)
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
        long distanceMeters
) {

    public static TheaterResponse from(Theater theater, double distanceMeters) {
        return new TheaterResponse(
                theater.getId(),
                theater.getName(),
                theater.getChainName(),
                theater.getAddress(),
                theater.getLatitude(),
                theater.getLongitude(),
                theater.getScreenCount(),
                theater.getSeatCount(),
                Math.round(distanceMeters)
        );
    }
}
