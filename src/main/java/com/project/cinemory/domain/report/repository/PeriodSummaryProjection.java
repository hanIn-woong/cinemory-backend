package com.project.cinemory.domain.report.repository;

/**
 * 기간 리포트(월간·연간) 기본 지표 3종(4-8-H). ⚠️ {@code averageRating}을 일부러 두지 않는다 —
 * 같은 쿼리에서 내면 회차 평균이 되어 집계형 규칙(기간 내 마지막 별점 회차)과 어긋난다.
 * 평균은 {@code findPeriodAverageRating}으로만 꺼낸다.
 */
public interface PeriodSummaryProjection {
    Long getMovieCount();
    Long getWatchCount();
    Long getTotalWatchedMinutes();
}
