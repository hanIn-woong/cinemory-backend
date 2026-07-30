package com.project.cinemory.domain.comment.dto;

import com.project.cinemory.domain.comment.entity.TargetType;

// content 길이/공백 검증(@NotBlank, @Size)은 Controller + @Valid를 도입하는 Step5에서 추가한다.
public record CommentCreateRequest(TargetType targetType, Long targetId, String content) {
}
