package com.project.cinemory.domain.report.repository;

import java.time.LocalDate;

/** 연간 "올해 5점을 준 작품"(4-8-H ⑨). {@code fiveStarDate}는 그해 처음 5점을 준 날. */
public interface FiveStarMovieProjection {
    Long getMovieId();
    String getTitle();
    String getPosterPath();
    LocalDate getFiveStarDate();
}
