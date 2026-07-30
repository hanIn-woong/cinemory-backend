package com.project.cinemory.domain.theater.service;

/**
 * 반경 검색용 좌표 계산 유틸.
 *
 * <p>DB에는 Bounding Box(사각형)로 후보를 좁히고, 정밀 거리(Haversine)는 여기서 계산한다.
 * 거리 계산식을 SQL {@code ORDER BY}에 넣으면 인덱스를 타지 못하고 전체 정렬이 발생하므로,
 * 후보를 좁힌 뒤 메모리에서 정렬하는 편이 싸다.
 */
public final class GeoUtils {

    /** 평균 지구 반지름(m) — IUGG 권장값 */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    /** 위도 1도의 거리(m). 위도와 무관하게 거의 일정하다. */
    private static final double METERS_PER_LAT_DEGREE = 111_320.0;

    private GeoUtils() {
    }

    /**
     * 중심점과 반경으로 Bounding Box를 만든다.
     *
     * <p>경도 1도의 거리는 위도에 따라 {@code cos(위도)}배로 줄어들기 때문에 보정이 필요하다.
     * 한국은 위도 33~38도 구간이라 극지방(cos → 0)이나 날짜변경선(경도 wrap-around)
     * 경계 처리가 필요 없어 분기 없이 단순 공식을 그대로 쓴다.
     */
    public static BoundingBox boundingBox(double latitude, double longitude, double radiusMeters) {
        double latDelta = radiusMeters / METERS_PER_LAT_DEGREE;
        double lngDelta = radiusMeters / (METERS_PER_LAT_DEGREE * Math.cos(Math.toRadians(latitude)));

        return new BoundingBox(
                latitude - latDelta,
                latitude + latDelta,
                longitude - lngDelta,
                longitude + lngDelta
        );
    }

    /** 두 지점 간 대권 거리(m). */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record BoundingBox(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
    }
}
