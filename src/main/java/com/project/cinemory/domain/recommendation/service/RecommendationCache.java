package com.project.cinemory.domain.recommendation.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.repository.ProfileFingerprint;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
 *   <li><b>취향 추천 묶음 상태</b>(R-5-3) — 키 {@code userId}. 현재·직전 묶음과 후보 풀. 수명 7일. 결과 캐시와 달리 <b>10분 만료가 아니다</b>:
 *       "오늘 그 영화"를 다시 찾을 수 있어야 하고, 직전 3묶음 제외 이력이 하루를 넘어 이어져야 매일 같은 추천이 되지 않는다.</li>
 * </ul>
 */
@Component
public class RecommendationCache {

    /** 확정된 순위 100편과 응답 메타. 조건·personalized는 계산 시점 값이다. */
    public record Entry(List<RankedMovie> movies, boolean personalized, List<AppliedConditionResponse> conditions) {
    }

    private record Guest(Object generation, Entry entry) {
    }

    /**
     * 한 사용자의 취향 추천 묶음 상태 (R-5-3).
     *
     * @param date         묶음을 뽑은 날(KST) - 날짜가 바뀌면 새 묶음
     * @param fingerprint  묶음을 뽑을 때의 프로필 지문 - 바뀌면 새 묶음
     * @param generation   풀을 계산할 때의 특징 인덱스 스냅샷. 바뀌면(06:00 재적재·관리자 덮어쓰기) 풀이 낡았을 수 있어 다시 계산한다
     * @param pool         후보 풀(S 내림차순). 기록·찜이 없는 사용자는 전역 공유 풀의 같은 목록을 가리킨다
     * @param recent       최근 묶음, <b>최신이 앞</b>({@code recent[0]}이 현재 묶음). 최대 {@code for-you.recent-batches}개
     */
    public record BatchState(LocalDate date, ProfileFingerprint fingerprint, Object generation, boolean personalized,
                             List<RankedMovie> pool, List<List<RankedMovie>> recent) {

        public List<RankedMovie> current() {
            return recent.get(0);
        }
    }

    private final Cache<String, Entry> results;
    private final Cache<String, float[]> queryEmbeddings;
    private final Cache<Long, BatchState> batchStates;
    private volatile Guest guest;

    public RecommendationCache(RecommendProperties properties) {
        this.results = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cache().maxResults())
                .build();
        this.batchStates = Caffeine.newBuilder()
                .expireAfterWrite(properties.forYou().stateTtl())
                .maximumSize(properties.forYou().stateMaxSize())
                .build();
        this.queryEmbeddings = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().queryEmbeddingTtl())
                .maximumSize(properties.cache().maxQueryEmbeddings())
                .build();
    }

    public static String queryKey(long userId, String normalizedQuery) {
        return "Q:" + userId + ":" + normalizedQuery;
    }

    /**
     * 탈퇴한 사용자의 결과를 지운다 — 취향 추천 묶음 상태와 {@code Q:{id}:} 접두 키.
     * ⚠️ 접두는 반드시 콜론까지 비교한다. {@code Q:1}로 비교하면 {@code Q:12:…}까지 지운다.
     */
    public void evictUser(long userId) {
        batchStates.invalidate(userId);
        String queryPrefix = "Q:" + userId + ":";
        results.asMap().keySet().removeIf(key -> key.startsWith(queryPrefix));
    }

    public BatchState getBatchState(long userId) {
        return batchStates.getIfPresent(userId);
    }

    public void putBatchState(long userId, BatchState state) {
        batchStates.put(userId, state);
    }

    /**
     * 사용자별 자연어 추천 결과를 모두 비운다 - 추천 제외 덮어쓰기가 바뀌었을 때(R-5-1). 게스트 전역 풀은 특징 인덱스 스냅샷 교체로 따로 재계산된다.
     * 질의 임베딩은 영화와 무관해 그대로 둔다. <b>취향 추천 묶음 상태는 비우지 않는다</b> - 직전 3묶음 제외 이력을 지키려는 것이고,
     * 낡은 풀·제외된 영화가 든 현재 묶음은 상태에 박힌 스냅샷 세대와 영화별 제외 여부 검사로 그 자리에서 걸러진다.
     */
    public void evictAllResults() {
        results.invalidateAll();
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
