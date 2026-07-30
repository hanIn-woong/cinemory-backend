package com.project.cinemory.domain.theater.dto;

import java.math.BigDecimal;

/**
 * 전국영화상영관표준데이터 1행.
 *
 * <p>좌표는 <b>WGS84 위경도</b>로 들어온다고 전제한다. 원본 파일이 EPSG:5174(중부원점 TM)라면
 * 이 DTO를 만들기 전 단계에서 변환해야 한다.
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
