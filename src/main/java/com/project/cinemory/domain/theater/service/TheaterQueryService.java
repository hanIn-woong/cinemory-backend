package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterResponse;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * CineMap 주변 극장 조회.
 *
 * <p>극장은 사용자 소유 데이터가 아닌 공용 데이터이므로 {@code UserAccessPolicy} 적용 대상이 아니다.
 * {@code viewerId}를 받지 않으며 비로그인 상태에서도 조회할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TheaterQueryService {

    private final TheaterRepository theaterRepository;

    @Value("${cinemory.theater.default-radius-meters}")
    private int defaultRadiusMeters;

    @Value("${cinemory.theater.max-radius-meters}")
    private int maxRadiusMeters;

    @Value("${cinemory.theater.max-result-size}")
    private int maxResultSize;

    /**
     * 중심 좌표 기준 반경 내 극장을 가까운 순으로 반환한다.
     *
     * @param radiusMeters null이면 기본 반경 사용
     * @param limit        null이면 최대 결과 수 사용
     */
    public List<TheaterResponse> getNearbyTheaters(double latitude, double longitude,
                                                   Integer radiusMeters, Integer limit) {
        int radius = resolveRadius(radiusMeters);
        int size = resolveLimit(limit);

        GeoUtils.BoundingBox box = GeoUtils.boundingBox(latitude, longitude, radius);

        List<Theater> candidates = theaterRepository.findWithinBoundingBox(
                BigDecimal.valueOf(box.minLatitude()),
                BigDecimal.valueOf(box.maxLatitude()),
                BigDecimal.valueOf(box.minLongitude()),
                BigDecimal.valueOf(box.maxLongitude())
        );

        // Bounding Box는 사각형이라 모서리에 반경 밖 항목이 걸린다 → Haversine으로 정밀 필터링
        return candidates.stream()
                .map(theater -> new TheaterDistance(theater, GeoUtils.haversineMeters(
                        latitude, longitude,
                        theater.getLatitude().doubleValue(), theater.getLongitude().doubleValue())))
                .filter(item -> item.distanceMeters() <= radius)
                .sorted(Comparator.comparingDouble(TheaterDistance::distanceMeters))
                .limit(size)
                .map(item -> TheaterResponse.from(item.theater(), item.distanceMeters()))
                .toList();
    }

    /**
     * 반경 상한을 두는 이유: 무제한을 허용하면 Bounding Box가 전국을 덮어 1차 필터가 무의미해지고
     * 전체 테이블을 읽게 된다.
     */
    private int resolveRadius(Integer radiusMeters) {
        if (radiusMeters == null) {
            return defaultRadiusMeters;
        }
        if (radiusMeters <= 0 || radiusMeters > maxRadiusMeters) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_RADIUS);
        }
        return radiusMeters;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return maxResultSize;
        }
        return Math.min(limit, maxResultSize);
    }

    private record TheaterDistance(Theater theater, double distanceMeters) {
    }
}
