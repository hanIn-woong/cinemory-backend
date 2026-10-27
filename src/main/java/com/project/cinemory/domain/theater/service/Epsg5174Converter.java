package com.project.cinemory.domain.theater.service;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * EPSG:5174(Bessel 중부원점 TM) → WGS84 변환(cinemap-spec C-4-4 ③, 마지막 대체 수단).
 *
 * <p>⚠️ Bessel→WGS84 datum 이동 파라미터({@code towgs84})가 틀리면 수백 m씩 어긋나도 오류 없이 저장된다 —
 * 정의를 proj4j 번들(epsg 파일)에 맡기지 않고 여기에 명시하고, {@code Epsg5174ConverterTest}가 알려진 지점으로 고정한다.
 */
public final class Epsg5174Converter {

    private static final String EPSG_5174 = "+proj=tmerc +lat_0=38 +lon_0=127.0028902777778 +k=1 "
            + "+x_0=200000 +y_0=500000 +ellps=bessel +units=m +no_defs "
            + "+towgs84=-115.80,474.99,674.11,1.16,-2.31,-1.63,6.43";
    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    private static final CoordinateTransform TRANSFORM;

    static {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem source = factory.createFromParameters("EPSG:5174", EPSG_5174);
        CoordinateReferenceSystem target = factory.createFromParameters("WGS84", WGS84);
        TRANSFORM = new CoordinateTransformFactory().createTransform(source, target);
    }

    private Epsg5174Converter() {
    }

    /** @return {@code {위도, 경도}} */
    public static synchronized double[] toWgs84(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORM.transform(new ProjCoordinate(x, y), out);
        return new double[]{out.y, out.x};
    }
}
