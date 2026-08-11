package com.project.cinemory.domain.comment.dto;

import com.project.cinemory.domain.comment.entity.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CommentCreateRequest(

        @NotNull(message = "대상 타입은 필수입니다.")
        TargetType targetType,

        @NotNull(message = "대상 id는 필수입니다.")
        Long targetId,

        @NotBlank(message = "댓글 내용은 필수입니다.")
        @Size(max = 500, message = "댓글 내용은 500자를 넘을 수 없습니다.")
        String content
) {
}
