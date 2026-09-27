package com.project.cinemory.domain.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 누적 통계. 필드 전체는 {@code M3a-report-spec.md} 6절 참고. */
public record ReportStatisticsResponse(
        long movieCount,
        long watchCount,
        long undatedCount,
        long totalWatchedMinutes,
        BigDecimal averageRating,
        List<RatingBucketResponse> ratingDistribution,
        List<PreferenceItemResponse> topGenres,
        List<PreferenceItemResponse> topCountries,
        List<PreferenceItemResponse> topActors,
        List<PreferenceItemResponse> topDirectors,
        List<MonthlyTrendItemResponse> monthlyTrend,
        List<WatchTypeCountResponse> watchTypeDistribution,
        List<OttPlatformCountResponse> ottPlatformDistribution,
        List<DecadeCountResponse> releaseDecadeDistribution,
        long classicCount,
        OldestWatchedResponse oldestWatched,
        BigDecimal ratingBiasAverage,
        MovieRatingGapResponse mostOverratedByMe,
        MovieRatingGapResponse mostUnderratedByMe,
        long rewatchCount,
        List<RewatchItemResponse> rewatchTop,
        List<WeekdayCountResponse> weekdayDistribution,
        LocalDate firstRecordDate,
        double reviewRate
) {
}
