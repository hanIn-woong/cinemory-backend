package com.project.cinemory.domain.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewWriteRequest(

        // 범위(0.0~10.0)는 Review.validateRating() 소관 — DTO는 null 여부만 본다(5-0-B).
        @NotNull(message = "평점은 필수입니다.")
        Double rating,

        @NotBlank(message = "리뷰 내용은 필수입니다.")
        @Size(max = 2000, message = "리뷰 내용은 2000자를 넘을 수 없습니다.")
        String content
) {
}
