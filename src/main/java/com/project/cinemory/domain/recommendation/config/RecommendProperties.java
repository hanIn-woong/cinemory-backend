package com.project.cinemory.domain.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        Duration cacheTtl) {

    /** T 내부 가중치 (S-1-2). */
    public record Taste(double genre, double country, double person) {
    }

    /** 내 취향 추천 S = taste·T + quality·Q + popularity·Pop (S-1-4). */
    public record ForYou(double taste, double quality, double popularity) {
    }

    /** 자연어 S = similarity·sim + taste·T + quality·Q + runtimeBonus·[런타임 선호 충족]. */
    public record Query(double similarity, double taste, double quality, double runtimeBonus, int candidateSize,
                        int preferShortMax, int preferLongMin) {
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

    /** 찜 신호 결합 (W-1). */
    public record Wish(double lambda, double smoothing, int minDirectorWishes) {
    }
}
