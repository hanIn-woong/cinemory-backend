package com.project.cinemory.domain.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 추천 점수의 가중치·상수 전부 (M3b-recommendation-spec S-1-7). 코드에 숫자를 박지 않는다.
 *
 * <p>{@code cinemory.recommend.embedding-sync.*}는 {@code EmbeddingSyncProperties}가 따로 받는다(이 레코드는 모르는 키를 무시한다).
 */
@ConfigurationProperties(prefix = "cinemory.recommend")
public record RecommendProperties(
        Taste taste,
        ForYou forYou,
        Query query,
        Quality quality,
        Popularity popularity,
        MinVoteCount minVoteCount,
        Wish wish,
        int maxPerDirector,
        int maxResults,
        int pageSize,
        Duration cacheTtl,
        Cache cache,
        RateLimit rateLimit) {

    /** T 내부 가중치 (S-1-2). */
    public record Taste(double genre, double country, double person) {
    }

    /**
     * 내 취향 추천 S = taste·T + quality·Q + popularity·Pop (S-1-4) + 10편 묶음 규칙 (R-5-3).
     *
     * @param batchSize     묶음 크기
     * @param poolSize      후보 풀 - S 점수 상위 N편(제외 규칙·R-5-1 적용 후, 감독 상한 적용 전)
     * @param recentBatches 직전 N묶음은 다음 묶음에서 제외한다
     * @param temperature   Gumbel-top-k의 온도 τ - 낮추면 고정, 높이면 섞인다
     * @param stateTtl      사용자별 묶음 상태(현재·직전 묶음·풀) 수명
     * @param stateMaxSize  사용자별 묶음 상태 최대 개수 - 사용자당 풀 50편(약 10KB)이라 힙 예산 안에서 상한을 둔다
     * @param maxExclude    게스트가 보내는 {@code exclude}의 최대 개수 - 초과분은 오래된(앞쪽) 것부터 자른다
     */
    public record ForYou(double taste, double quality, double popularity,
                         @DefaultValue("10") int batchSize,
                         @DefaultValue("50") int poolSize,
                         @DefaultValue("3") int recentBatches,
                         @DefaultValue("0.05") double temperature,
                         @DefaultValue("7d") Duration stateTtl,
                         @DefaultValue("3000") int stateMaxSize,
                         @DefaultValue("30") int maxExclude) {
    }

    /**
     * 자연어 S = similarity·sim + taste·T + quality·Q + runtimeBonus·[런타임 선호 충족] − negativePenalty·negSim.
     *
     * @param negativePenalty 부정 개념 감점 γ(Q-2-3 ②). 0이면 감점을 끈다
     * @param maxNegatives    부정 개념 최대 개수 — 더 나오면 호출이 늘고 감점이 상쇄된다
     * @param recentYears     "최근 영화" = 오늘 − N년 이후(Q-2-2)
     * @param oldYears        "옛날 영화" = 오늘 − N년 이전
     */
    public record Query(double similarity, double taste, double quality, double runtimeBonus, int candidateSize,
                        int preferShortMax, int preferLongMin,
                        double negativePenalty, int maxNegatives, int recentYears, int oldYears) {
    }

    /** Q = (v·R + k·M) / (v + k) / 10 — {@code mean} = M, {@code minVotes} = k (S-1-3). */
    public record Quality(double mean, double minVotes) {
    }

    /** Pop의 그룹별 99분위 투표 수 (S-1-3-B ②). */
    public record Popularity(double v99Korean, double v99Other) {
    }

    /** 투표 수 하한 (S-1-3-B ①) — 한국은 0(하한 없음). */
    public record MinVoteCount(int korean, int other) {
    }

    /** 캐시 상한·수명 (controller-layer-spec 5-14-D) — 결과 1,000개 ≈ 10MB, 임베딩 1,000개 ≈ 3MB(힙 640MB 예산). */
    public record Cache(int maxResults, int maxQueryEmbeddings, Duration queryEmbeddingTtl) {
    }

    /** 자연어 추천 호출 제한 — 사용자당. 실제 Gemini 호출만 센다(5-14-E). 일 단위는 KST 자정 기준. */
    public record RateLimit(int perMinute, int perDay) {
    }

    /** 찜 신호 결합 (W-1). */
    public record Wish(double lambda, double smoothing, int minDirectorWishes) {
    }
}
