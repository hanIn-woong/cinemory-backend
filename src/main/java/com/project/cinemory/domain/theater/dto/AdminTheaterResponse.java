package com.project.cinemory.domain.theater.dto;

import com.project.cinemory.domain.theater.entity.Theater;

import java.math.BigDecimal;
import java.time.Instant;

/** 관리자 극장 목록·변경 응답(cinemap-spec C-8-2). 숨긴 극장도 나오며 {@code closedAt}이 있으면 숨김이다. */
public record AdminTheaterResponse(
        Long theaterId,
        String sourceCode,
        String name,
        String chainName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer screenCount,
        String bookingUrl,      // 극장에 직접 입력한 링크만. 체인 기본 URL은 포함하지 않는다
        Instant closedAt        // null이면 영업 중
) {

    public static AdminTheaterResponse from(Theater theater) {
        return new AdminTheaterResponse(theater.getId(), theater.getSourceCode(), theater.getName(), theater.getChainName(),
                theater.getAddress(), theater.getLatitude(), theater.getLongitude(), theater.getScreenCount(),
                theater.getBookingUrl(), theater.getClosedAt());
    }
}
