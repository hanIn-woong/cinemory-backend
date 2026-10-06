package com.project.cinemory.domain.watch.repository;

import java.math.BigDecimal;

/** {@link WatchRecordRepository#findCinemoryRatingByMovieId} 전용 집계 프로젝션. */
public interface MovieRatingProjection {
    BigDecimal getAverage(); // 별점 준 사용자가 없으면 null
    Long getCount();         // 없으면 0
}
