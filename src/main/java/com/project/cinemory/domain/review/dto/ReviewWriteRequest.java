package com.project.cinemory.domain.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewWriteRequest(

        @NotBlank(message = "리뷰 내용은 필수입니다.")
        @Size(max = 10000, message = "리뷰 내용은 10,000자를 넘을 수 없습니다.")
        String content
) {
}
