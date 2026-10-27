package com.project.cinemory.domain.theater.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알려진 지점으로 변환 파라미터를 고정한다(cinemap-spec C-4-4 ③).
 * 샘플은 스펙의 속초 행(롯데시네마 속초, 중앙로121번길 28 — 센텀마크빌딩, 속초시 금호동).
 *
 * <p>⚠️ 이 테스트는 "속초 시내(반경 1km)"까지만 고정한다. 수십 m 단위 정확도는 카카오 주소 검색과의 대조가 필요하고,
 * dryRun 보고서의 {@code coordinates.tmCheck}가 전 극장에 대해 그 비교를 한다.
 */
class Epsg5174ConverterTest {

    // 속초시청 근처(금호동 일대) — 변환 결과가 이 점의 1km 안이어야 한다
    private static final double SOKCHO_LAT = 38.2070;
    private static final double SOKCHO_LNG = 128.5918;

    @Test
    void 속초_샘플이_속초_시내로_변환된다() {
        double[] ll = Epsg5174Converter.toWgs84(339101.58, 523440.60);

        double meters = GeoUtils.haversineMeters(SOKCHO_LAT, SOKCHO_LNG, ll[0], ll[1]);
        assertThat(meters).as("변환 결과 %s,%s", ll[0], ll[1]).isLessThan(1_000);
    }

    @Test
    void 원점은_위도38_경도127도_부근이다() {
        // 원점(x=200000, y=500000) = 북위 38°, 동경 127.0028902777778° (datum 이동만큼 수백 m 이내 차이)
        double[] ll = Epsg5174Converter.toWgs84(200_000, 500_000);

        assertThat(ll[0]).isBetween(37.99, 38.01);
        assertThat(ll[1]).isBetween(126.99, 127.02);
    }
}
