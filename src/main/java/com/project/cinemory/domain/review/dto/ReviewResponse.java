package com.project.cinemory.domain.review.dto;

import com.project.cinemory.domain.review.entity.Review;

import java.time.LocalDateTime;

public record ReviewResponse(
        Long id,
        ReviewAuthorResponse author,

        /**
         * 저장 컬럼이 아니라 파생값이다(v15에서 {@code review.rating} 제거).
         * 대표 시청 기록의 rating → null이면 rating IS NOT NULL인 가장 최근(id DESC) 기록의
         * rating → 그것도 없으면 null. 호출부(ReviewService)가 미리 조회해 넘긴다.
         */
        Double rating,

        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReviewResponse of(Review review, Double rating) {
        return new ReviewResponse(
                review.getId(),
                ReviewAuthorResponse.from(review.getUser()),
                rating,
                review.getContent(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
