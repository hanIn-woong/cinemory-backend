package com.project.cinemory.domain.theater.dto;

import com.project.cinemory.domain.theater.entity.Theater;

import java.math.BigDecimal;
import java.time.Instant;

/** 관리자 극장 목록·변경 응답(cinemap-spec C-8-2). 숨긴 극장도 나오며 {@code closedAt}이 있으면 숨김이다. */
public record AdminTheaterResponse(
        Long theaterId,
        String sourceCode,
        String name,            // 표시 이름 = nameOverride ?? 규칙 이름
        String sourceName,      // 원천이 준 이름(정규화 전). 아직 동기화 전이면 null
        String nameOverride,    // 관리자가 정한 이름. null이면 규칙 이름
        String chainName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer screenCount,
        String bookingUrl,      // 극장에 직접 입력한 링크만. 체인 기본 URL은 포함하지 않는다
        Instant closedAt        // null이면 영업 중
) {

    public static AdminTheaterResponse from(Theater theater) {
        return new AdminTheaterResponse(theater.getId(), theater.getSourceCode(), theater.getName(),
                theater.getSourceName(), theater.getNameOverride(), theater.getChainName(),
                theater.getAddress(), theater.getLatitude(), theater.getLongitude(), theater.getScreenCount(),
                theater.getBookingUrl(), theater.getClosedAt());
    }
}
