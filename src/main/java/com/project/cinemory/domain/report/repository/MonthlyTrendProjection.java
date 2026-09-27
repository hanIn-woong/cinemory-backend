package com.project.cinemory.domain.report.repository;

/** 월별 시계열 원본 행. 기록 있는 달만 돌려주므로 공백 달 채우기는 Service 책임(4-2). */
public interface MonthlyTrendProjection {
    Integer getYear();
    Integer getMonth();
    Long getWatchCount();
    Long getMovieCount();
    Long getWatchedMinutes();
}
