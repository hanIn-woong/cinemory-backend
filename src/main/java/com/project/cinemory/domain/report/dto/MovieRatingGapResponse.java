package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.MovieRatingGapProjection;

import java.math.BigDecimal;

public record MovieRatingGapResponse(Long movieId, String title, String posterPath,
                                      BigDecimal myRating, BigDecimal publicRating, BigDecimal gap) {

    public static MovieRatingGapResponse from(MovieRatingGapProjection projection) {
        return new MovieRatingGapResponse(projection.getMovieId(), projection.getTitle(), projection.getPosterPath(),
                projection.getMyRating(), projection.getPublicRating(), projection.getGap());
    }
}
