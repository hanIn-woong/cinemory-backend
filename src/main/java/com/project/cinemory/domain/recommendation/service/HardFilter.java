package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ExclusionCounts;

import java.time.LocalDate;

/**
 * 영화 한 편이 추천 후보에서 빠지는가 — 하드 필터 판정과 제외 사유별 집계 (M3b-recommendation-spec S-1-4 · Q-2-4).
 *
 * <p>취향 추천과 자연어 추천이 같은 판정을 쓴다(자연어가 {@code degraded}로 취향 추천 공식에 대체돼도 질의의 하드 필터가 유지돼야 한다).
 * 자연어는 후보를 뽑는 <b>전수 스캔 중에</b> 이 판정을 부르므로 필터 비용이 따로 들지 않는다. 판정 순서가 곧 집계 우선순위다 —
 * 한 영화는 처음 걸린 사유 하나로만 센다.
 *
 * <p><b>상태를 갖는다</b>(집계 카운터) — 요청마다 새로 만들고 한 스레드에서만 쓴다.
 */
final class HardFilter {

    enum Reason { WATCHED, WISHED, UNRELEASED, VOTE_FLOOR, RUNTIME, COUNTRY, RELEASE_PERIOD, EXCLUDED_GENRE }

    private final QueryConstraints constraints;
    private final RecommendationScorer scorer;
    private final UserPreferenceProfile profile;
    private final boolean excludeWishedAndUnreleased;
    private final LocalDate today;
    private final int[] counts = new int[Reason.values().length];

    /**
     * @param forYou true면 취향 추천 규칙(찜한 영화 · 개봉 전/개봉일 없음 제외)을 더한다. 자연어는 찜을 {@code wished}로 표시만 하고 개봉 전도 거르지 않는다.
     * @param today  {@code forYou}일 때의 오늘(Clock KST)
     */
    HardFilter(QueryConstraints constraints, RecommendationScorer scorer, UserPreferenceProfile profile, boolean forYou, LocalDate today) {
        this.constraints = constraints == null ? QueryConstraints.none() : constraints;
        this.scorer = scorer;
        this.profile = profile;
        this.excludeWishedAndUnreleased = forYou;
        this.today = today;
    }

    /** 통과하면 {@code true}. 걸리면 사유를 한 번 센다. */
    boolean allows(MovieFeature movie) {
        Reason reason = reject(movie);
        if (reason == null) {
            return true;
        }
        counts[reason.ordinal()]++;
        return false;
    }

    private Reason reject(MovieFeature movie) {
        if (profile.watchedMovieIds().contains(movie.movieId())) {
            return Reason.WATCHED;
        }
        if (excludeWishedAndUnreleased) {
            if (profile.wishedMovieIds().contains(movie.movieId())) {
                return Reason.WISHED;
            }
            if (movie.releaseDate() == null || movie.releaseDate().isAfter(today)) {
                return Reason.UNRELEASED;
            }
        }
        if (!scorer.passesVoteFloor(movie)) {
            return Reason.VOTE_FLOOR;
        }
        if (constraints.runtimeMax() != null && (movie.runtime() == null || movie.runtime() > constraints.runtimeMax())) {
            return Reason.RUNTIME;   // 하드 필터에서 runtime이 null인 영화는 제외한다(Q-1)
        }
        String country = movie.representativeCountryCode();
        if (!constraints.countries().isEmpty() && (country == null || !constraints.countries().contains(country))) {
            return Reason.COUNTRY;
        }
        if (country != null && constraints.excludeCountries().contains(country)) {
            return Reason.COUNTRY;
        }
        if (constraints.releasePeriod() != null && !constraints.releasePeriod().contains(movie.releaseDate())) {
            return Reason.RELEASE_PERIOD;   // release_date가 null이면 시기 필터에서 제외된다(Q-2-2)
        }
        if (!constraints.excludeGenres().isEmpty()) {
            for (int tmdbId : movie.genreTmdbIds()) {
                if (constraints.excludeGenres().contains(tmdbId)) {
                    return Reason.EXCLUDED_GENRE;
                }
            }
        }
        return null;
    }

    int count(Reason reason) {
        return counts[reason.ordinal()];
    }

    ExclusionCounts toCounts(int directorCap) {
        return new ExclusionCounts(count(Reason.WATCHED), count(Reason.WISHED), count(Reason.UNRELEASED),
                count(Reason.VOTE_FLOOR), count(Reason.RUNTIME), directorCap,
                count(Reason.COUNTRY), count(Reason.RELEASE_PERIOD), count(Reason.EXCLUDED_GENRE));
    }
}
