package com.project.cinemory.domain.theater.dto;

import com.project.cinemory.domain.theater.entity.Theater;

/**
 * 기록·검색에 쓰는 극장 요약(service-layer-spec 4-3-C). {@code closed}는 폐업(숨김) 여부 - 기록 화면이 "폐업"을 표시한다.
 */
public record TheaterSummaryResponse(Long id, String name, String chainName, boolean closed) {

    public static TheaterSummaryResponse from(Theater theater) {
        return new TheaterSummaryResponse(theater.getId(), theater.getName(), theater.getChainName(), theater.isClosed());
    }
}
