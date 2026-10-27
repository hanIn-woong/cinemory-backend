package com.project.cinemory.domain.theater.dto;

import java.math.BigDecimal;

/**
 * 극장 단위로 묶고 좌표까지 정한 저장 입력(cinemap-spec C-4). 좌표는 <b>WGS84 위경도</b>다 —
 * 원천의 EPSG:5174 변환은 이 DTO를 만들기 전 단계({@code TheaterSeedService})에서 끝난다.
 */
public record TheaterSeedData(
        String sourceCode,
        String name,
        String chainName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer screenCount,
        Integer seatCount
) {
}
