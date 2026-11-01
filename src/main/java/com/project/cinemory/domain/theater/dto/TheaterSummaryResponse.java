package com.project.cinemory.domain.theater.dto;

import com.project.cinemory.domain.theater.entity.Theater;

/**
 * 기록·검색에 쓰는 극장 요약(service-layer-spec 4-3-C). {@code closed}는 폐업(숨김) 여부다.
 * {@code address}는 이름이 같은 지점(예: 이름이 "메가박스"뿐인 여러 행)을 목록에서 구분하는 데 쓴다(4-3-C ④ 개정).
 */
public record TheaterSummaryResponse(Long id, String name, String chainName, String address, boolean closed) {

    public static TheaterSummaryResponse from(Theater theater) {
        return new TheaterSummaryResponse(theater.getId(), theater.getName(), theater.getChainName(), theater.getAddress(),
                theater.isClosed());
    }
}
