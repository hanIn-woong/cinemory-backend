package com.project.cinemory.domain.report.repository;

/** 누적/월별 기본 지표 5종. {@code averageRating}만 대표+별점 조건부 집계라 나머지와 기준이 다르다(4-8-A). */
public interface ReportSummaryProjection {
    Long getMovieCount();
    Long getWatchCount();
    Long getUndatedCount();
    Long getTotalWatchedMinutes();
    Double getAverageRating();
}
