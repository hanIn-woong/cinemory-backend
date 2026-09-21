package com.project.cinemory.domain.report.dto;

import java.math.BigDecimal;
import java.util.List;

/** 월말 리포트. 누적({@code ReportStatisticsResponse})의 부분집합 + 월 한정 지표. */
public record ReportMonthlyResponse(
        int year,
        int month,
        long movieCount,
        long watchCount,
        long totalWatchedMinutes,
        BigDecimal averageRating,
        List<RatingBucketResponse> ratingDistribution,
        List<WatchTypeCountResponse> watchTypeDistribution,
        PreferenceItemResponse mostWatchedDirector,
        Integer mostWatchedWeekday
) {
}
