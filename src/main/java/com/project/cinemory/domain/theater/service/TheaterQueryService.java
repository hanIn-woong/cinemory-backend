package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterResponse;
import com.project.cinemory.domain.theater.dto.TheaterSummaryResponse;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.global.dto.LikePattern;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final TheaterBookingResolver bookingResolver;
    private final WatchRecordRepository watchRecordRepository;

    private static final int SEARCH_DEFAULT_LIMIT = 20;
    private static final int SEARCH_MAX_LIMIT = 50;
    private static final int SEARCH_MAX_WORDS = 3;
    private static final int RECENT_LIMIT = 5;

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
                .map(item -> TheaterResponse.from(item.theater(), item.distanceMeters(), bookingResolver.resolve(item.theater())))
                .toList();
    }

    /**
     * 기록에 연결할 극장 검색(service-layer-spec 4-3-C) — 영업 중만. 검색어가 비면 400({@code /movies/search}와 같은 서비스 검증).
     *
     * <p>release-build-spec §7 O-3: 목록을 자르지 않고 <b>페이지</b>로 준다(0-based). 검색어는 공백으로 나눈 단어(최대 3개)가
     * 모두 이름·체인·주소 중 어딘가에 들어 있어야 일치한다 — "메가박스 강남"이 체인 메가박스 AND 강남으로 찾힌다.
     *
     * @param page null이면 0
     * @param size null이면 20, 상한 50
     */
    public Page<TheaterSummaryResponse> searchTheaters(String q, Integer page, Integer size) {
        if (q == null || q.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int resolvedPage = (page == null || page < 0) ? 0 : page;
        int resolvedSize = (size == null || size <= 0) ? SEARCH_DEFAULT_LIMIT : Math.min(size, SEARCH_MAX_LIMIT);

        String[] words = q.trim().split("\\s+");
        String[] patterns = {"%", "%", "%"};
        for (int i = 0; i < Math.min(words.length, SEARCH_MAX_WORDS); i++) {
            patterns[i] = LikePattern.contains(words[i]);
        }
        return theaterRepository
                .searchOpenByWords(patterns[0], patterns[1], patterns[2], LikePattern.startsWith(words[0]),
                        PageRequest.of(resolvedPage, resolvedSize))
                .map(TheaterSummaryResponse::from);
    }

    /** 내가 최근 기록한 극장 최대 5곳(폐업 제외, 중복 없음) — 가장 최근에 고른 것부터. */
    public List<TheaterSummaryResponse> getRecentTheaters(Long userId) {
        List<Long> ids = watchRecordRepository.findRecentOpenTheaterIds(userId, PageRequest.of(0, RECENT_LIMIT));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Theater> byId = theaterRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Theater::getId, Function.identity()));
        return ids.stream().map(byId::get).map(TheaterSummaryResponse::from).toList();
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
