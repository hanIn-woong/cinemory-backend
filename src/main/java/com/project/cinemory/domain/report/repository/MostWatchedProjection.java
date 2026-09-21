package com.project.cinemory.domain.report.repository;

/** 월말 리포트의 "가장 많이 본 감독" — count 기준 단건(RA-4). 누적의 {@code PreferenceProjection}과 달리 score가 없다. */
public interface MostWatchedProjection {
    Long getId();
    String getName();
    Long getCount();
}
