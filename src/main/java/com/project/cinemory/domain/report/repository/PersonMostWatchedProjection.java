package com.project.cinemory.domain.report.repository;

/**
 * 기간 리포트의 "많이 본 감독·배우" — {@link MostWatchedProjection} + {@code profilePath}(4-8-I).
 * 장르·국가 쿼리는 이 별칭을 SELECT하지 않으므로 projection을 공유하지 않는다.
 */
public interface PersonMostWatchedProjection {
    Long getId();
    String getName();
    String getProfilePath();
    Long getCount();
}
