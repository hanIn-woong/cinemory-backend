package com.project.cinemory.domain.report.repository;

import java.math.BigDecimal;

/** 대중 평점과의 차이(4-5) — {@code mostOverratedByMe}/{@code mostUnderratedByMe} 공용. */
public interface MovieRatingGapProjection {
    Long getMovieId();
    String getTitle();
    String getPosterPath();
    BigDecimal getMyRating();
    BigDecimal getPublicRating();
    BigDecimal getGap();
}
