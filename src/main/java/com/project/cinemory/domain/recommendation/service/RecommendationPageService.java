package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.RecommendationResponse;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.RecommendationCache.Entry;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import com.project.cinemory.global.infra.gemini.EmbeddingClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

/**
 * 추천 API의 조립 계층 — 캐시 · 페이징 · 호출 제한 · 대체(degraded) · 응답 DTO (controller-layer-spec 5-14-D~F).
 * 점수 계산 자체는 {@link ForYouRecommendationService}·{@link QueryRecommendationService}가 한다.
 *
 * <p><b>이 클래스에는 트랜잭션이 없다.</b> 질의 임베딩 호출(최대 3초)을 커넥션을 쥔 채 기다리지 않도록, 영화·장르 조회는
 * 별도 빈({@link RecommendationResponseAssembler}, {@code @Transactional})이 전담한다(tmdb-sync-spec 6-4와 같은 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationPageService {

    /** 요청 {@code size} 상한 — Spring의 max-page-size(100)보다 먼저 이 값으로 자른다. */
    static final int MAX_PAGE_SIZE = 50;
    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 100;

    private final ForYouRecommendationService forYouService;
    private final QueryRecommendationService queryService;
    private final UserPreferenceProfileService profileService;
    private final QueryConstraintExtractor constraintExtractor;
    private final RecommendationCache cache;
    private final RecommendationRateLimiter rateLimiter;
    private final EmbeddingClient embeddingClient;
    private final MovieVectorIndex vectorIndex;
    private final MovieFeatureIndex featureIndex;
    private final RecommendationResponseAssembler assembler;

    // ── 내 취향 추천 ────────────────────────────────────────────────

    /**
     * @param userId 비로그인이면 {@code null} — 게스트는 콜드 스타트 결과(전역 공유 캐시)를 받는다
     */
    public RecommendationResponse forYou(Long userId, int page, int size) {
        int pageSize = clampSize(size);
        Entry entry;
        if (userId == null) {
            entry = guestEntry();
        } else if (page >= 1 && cache.getResult(RecommendationCache.forYouKey(userId)) != null) {
            // 2페이지부터는 계산 없이 캐시 슬라이스
            entry = cache.getResult(RecommendationCache.forYouKey(userId));
        } else {
            UserPreferenceProfile profile = profileService.build(userId);
            if (!profile.hasActivity()) {
                // 기록도 찜도 없으면 결과가 게스트와 같다 — 전역 공유 캐시를 쓰고 0페이지도 재계산하지 않는다.
                entry = guestEntry();
            } else {
                // 0페이지는 항상 재계산해 캐시를 교체한다(기록·찜 직후 재진입이 곧 갱신 — 무효화 이벤트가 필요 없다).
                entry = toEntry(forYouService.recommend(profile, QueryConstraints.none()));
                cache.putResult(RecommendationCache.forYouKey(userId), entry);
            }
        }
        return assembler.assemble(entry, page, pageSize, false);
    }

    private Entry guestEntry() {
        // 특징 인덱스 스냅샷이 바뀔 때(기동 후 첫 적재 · 06:00 배치 직후)만 다시 계산한다.
        return cache.guest(featureIndex.snapshot(),
                () -> toEntry(forYouService.recommend(UserPreferenceProfile.empty(), QueryConstraints.none())));
    }

    // ── 자연어로 찾기 ───────────────────────────────────────────────

    /** @param rawQuery 정규화 전 질의 — 정규화·검증은 여기서 한다(5-0-B: 검증은 Service). */
    public RecommendationResponse search(long userId, String rawQuery, int page, int size) {
        String query = normalizeQuery(rawQuery);
        int pageSize = clampSize(size);
        String key = RecommendationCache.queryKey(userId, query);

        if (page >= 1) {
            Entry cached = cache.getResult(key);
            if (cached != null) {
                return assembler.assemble(cached, page, pageSize, false);
            }
            // 캐시가 만료됐으면 0페이지처럼 다시 계산한다.
        }

        UserPreferenceProfile profile = profileService.build(userId);
        QueryConstraints constraints = constraintExtractor.extract(query);

        // 후보는 벡터 인덱스의 위치에서 뽑는다 — 인덱스가 비었으면(첫 배포 직후 등) Gemini를 부르지도 세지도 않고 대체한다.
        if (vectorIndex.isEmpty()) {
            log.warn("벡터 인덱스가 비어 있어 자연어 추천을 취향 추천으로 대체합니다.");
            return degraded(profile, constraints, page, pageSize);
        }

        // 본 질의(부정 구간을 지운 질의)와 부정 개념을 각각 임베딩한다. 실제 호출은 각각 호출 제한에 센다(캐시 적중은 제외).
        float[] mainVec = null;
        if (constraints.mainQuery() != null) {
            mainVec = embedOrNull(userId, constraints.mainQuery());
            if (mainVec == null) {
                return degraded(profile, constraints, page, pageSize);
            }
        }
        // 부정 개념 임베딩이 실패하면 그 개념의 감점만 생략한다(null) — 본 질의가 성공했으므로 degraded가 아니다(Q-2-3).
        List<float[]> negVecs = new ArrayList<>(constraints.negativeConcepts().size());
        for (String concept : constraints.negativeConcepts()) {
            negVecs.add(embedOrNull(userId, concept));
        }
        // 본 질의가 비어 있고(sim = 0) 감점할 벡터마저 하나도 없으면 순위를 매길 질의 신호가 없다 → 대체.
        if (mainVec == null && !negVecs.isEmpty() && negVecs.stream().allMatch(Objects::isNull)) {
            return degraded(profile, constraints, page, pageSize);
        }

        RecommendationResult result = queryService.recommend(profile, constraints, mainVec, negVecs);
        Entry entry = toEntry(result);
        cache.putResult(key, entry);
        return assembler.assemble(entry, page, pageSize, false);
    }

    /**
     * 대체 결과 — 취향 추천 공식 + 질의의 하드 필터(런타임·국가·개봉 시기·제외 장르)를 유지한다(5-14-F). 캐시하지 않는다(다음 요청에서 다시 시도).
     * 감점이 적용되지 않았으므로 AVOID_MOOD 칩은 내리지 않는다.
     */
    private RecommendationResponse degraded(UserPreferenceProfile profile, QueryConstraints constraints, int page, int pageSize) {
        RecommendationResult fallback = forYouService.recommend(profile, constraints);
        Entry entry = new Entry(fallback.movies(), fallback.personalized(), constraints.conditionsWithoutMood());
        return assembler.assemble(entry, page, pageSize, true);
    }

    /**
     * 텍스트 하나의 임베딩을 얻는다. Gemini가 실패/타임아웃이면 {@code null}. 질의 임베딩 캐시는 사용자와 무관하게 공유한다.
     * 호출 제한 초과는 대체하지 않고 예외(429)로 끝낸다.
     */
    private float[] embedOrNull(long userId, String text) {
        float[] cached = cache.getQueryEmbedding(text);
        if (cached != null) {
            return cached;   // 임베딩 캐시 적중은 호출 제한에 세지 않는다
        }
        rateLimiter.acquire(userId);   // 실제 Gemini 호출 직전에만 센다
        try {
            float[] vector = embeddingClient.embedQuery(text);
            cache.putQueryEmbedding(text, vector);
            return vector;
        } catch (EmbeddingClientException e) {
            // 원인은 로그에만 — 응답에는 싣지 않는다. 메시지에 질의 내용은 없다.
            log.warn("질의 임베딩에 실패했습니다. reason={}", e.getMessage());
            return null;
        }
    }

    // ── 공통 ───────────────────────────────────────────────────────

    /** 앞뒤 공백 제거 · 연속 공백 1개 · 영문 소문자 · NFC. 정규화 후 2~100자(코드포인트) 밖이면 400. */
    static String normalizeQuery(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_QUERY_LENGTH || length > MAX_QUERY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_RECOMMENDATION_QUERY);
        }
        return normalized;
    }

    private static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    private static Entry toEntry(RecommendationResult result) {
        return new Entry(result.movies(), result.personalized(), result.conditions());
    }
}
