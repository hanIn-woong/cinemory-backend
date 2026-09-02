package com.project.cinemory.domain.review.repository;

/** {@link ReviewRepository#findResolvedRatingsByReviewIds} 전용 벌크 조회 프로젝션. */
public interface ReviewRatingProjection {
    Long getReviewId();
    Double getRating();
}
