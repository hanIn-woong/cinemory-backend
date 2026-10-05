package com.project.cinemory.domain.report.repository;

/** 기간 리포트의 "많이 본 감독·배우·장르·국가" — 편수(count) 기준(RA-4, 4-8-H ⑩). 누적의 {@code PreferenceProjection}과 달리 score가 없다. */
public interface MostWatchedProjection {
    Long getId();
    String getName();
    Long getCount();
}
