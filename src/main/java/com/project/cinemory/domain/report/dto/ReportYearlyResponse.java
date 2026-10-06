package com.project.cinemory.domain.report.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 연간 리포트(M3a 10-4). 월간 지표 전부 + 연간 전용. 기간 리포트 규칙(4-8-H)을 따르며
 * {@code is_representative}를 보지 않는다.
 *
 * <p>⚠️ {@code fiveStarMovies}(목록형) 수와 {@code ratingDistribution}(집계형)의 10점 막대는
 * 다를 수 있다 — 같은 해 5점 뒤 재관람으로 다른 별점을 줬다면 목록에는 남고 분포에는 마지막 별점이 들어간다.
 */
public record ReportYearlyResponse(
        int year,
        long movieCount,
        long watchCount,
        long totalWatchedMinutes,
        BigDecimal averageRating,
        List<RatingBucketResponse> ratingDistribution,
        List<WatchTypeCountResponse> watchTypeDistribution,
        PersonRankItemResponse mostWatchedDirector,
        PersonRankItemResponse mostWatchedActor,
        List<PreferenceItemResponse> mostWatchedGenres,
        List<PreferenceItemResponse> mostWatchedCountries,
        List<MonthlyTrendItemResponse> monthlyTrend,
        List<WeekdayCountResponse> weekdayDistribution,
        List<FiveStarMovieResponse> fiveStarMovies
) {
}
