package com.project.cinemory.domain.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentUpdateRequest(

        @NotBlank(message = "댓글 내용은 필수입니다.")
        @Size(max = 500, message = "댓글 내용은 500자를 넘을 수 없습니다.")
        String content
) {
}
