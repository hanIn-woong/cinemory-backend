package com.project.cinemory.domain.recommendation.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 추천 결과·질의 임베딩 캐시 (controller-layer-spec 5-14-D, M3b-recommendation-spec A-1-3). 힙 640MB 예산 안에서 상한을 둔다.
 *
 * <ul>
 *   <li><b>결과</b> — 키: 취향 {@code FY:userId} · 자연어 {@code Q:userId:정규화 질의}. 수명 10분.
 *       순위 100편을 확정해 두고 2페이지부터 잘라 쓴다(중복·누락, 다양성 상한 틀어짐 방지).</li>
 *   <li><b>게스트·무활동 사용자 전역 1개</b> — 결과가 누구에게나 같다. 만료가 없고 <b>특징 인덱스 스냅샷이 바뀔 때만</b> 다시 계산한다
 *       (기동 후 첫 적재 · 06:00 배치 직후). ⚠️ 인증·호출 제한이 없는 공개 경로에서 요청마다 1.3만 편을 채점하면 반복 호출만으로
 *       CPU를 소모시킬 수 있어 0페이지도 재계산하지 않는다.</li>
 *   <li><b>질의 임베딩</b> — 사용자와 무관하게 공유(질의 벡터에 사용자 정보가 없다). 수명 24시간.</li>
 * </ul>
 */
@Component
public class RecommendationCache {

    /** 확정된 순위 100편과 응답 메타. 조건·personalized는 계산 시점 값이다. */
    public record Entry(List<RankedMovie> movies, boolean personalized, List<AppliedConditionResponse> conditions) {
    }

    private record Guest(Object generation, Entry entry) {
    }

    private final Cache<String, Entry> results;
    private final Cache<String, float[]> queryEmbeddings;
    private volatile Guest guest;

    public RecommendationCache(RecommendProperties properties) {
        this.results = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cache().maxResults())
                .build();
        this.queryEmbeddings = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().queryEmbeddingTtl())
                .maximumSize(properties.cache().maxQueryEmbeddings())
                .build();
    }

    public static String forYouKey(long userId) {
        return "FY:" + userId;
    }

    public static String queryKey(long userId, String normalizedQuery) {
        return "Q:" + userId + ":" + normalizedQuery;
    }

    public Entry getResult(String key) {
        return results.getIfPresent(key);
    }

    public void putResult(String key, Entry entry) {
        results.put(key, entry);
    }

    /**
     * 전역 공유 결과. {@code generation}(특징 인덱스 스냅샷)이 이전 계산 때와 다르면 {@code compute}로 다시 만든다.
     * 동시에 첫 요청이 몰려도 한 번만 계산한다.
     */
    public Entry guest(Object generation, Supplier<Entry> compute) {
        Guest current = guest;
        if (current != null && current.generation() == generation) {
            return current.entry();
        }
        synchronized (this) {
            current = guest;
            if (current != null && current.generation() == generation) {
                return current.entry();
            }
            Entry entry = compute.get();
            guest = new Guest(generation, entry);
            return entry;
        }
    }

    public float[] getQueryEmbedding(String normalizedQuery) {
        return queryEmbeddings.getIfPresent(normalizedQuery);
    }

    public void putQueryEmbedding(String normalizedQuery, float[] vector) {
        queryEmbeddings.put(normalizedQuery, vector);
    }
}
