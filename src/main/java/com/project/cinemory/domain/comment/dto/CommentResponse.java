package com.project.cinemory.domain.comment.dto;

import com.project.cinemory.domain.comment.entity.Comment;

import java.time.LocalDateTime;

/**
 * 댓글 응답.
 *
 * <p>{@code editable}/{@code deletable}은 조회자 기준 계산값이다.
 * 클라이언트가 권한 판정을 재구현하지 않도록 서버가 계산해 내려준다.
 * <ul>
 *   <li>수정: 작성자 본인만</li>
 *   <li>삭제: 작성자 본인 또는 댓글이 달린 대상(컬렉션/리뷰)의 소유자</li>
 * </ul>
 */
public record CommentResponse(
        Long commentId,
        CommentAuthorResponse author,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean editable,
        boolean deletable
) {

    public static CommentResponse from(Comment comment, Long viewerId, Long targetOwnerId) {
        boolean mine = viewerId != null && viewerId.equals(comment.getUser().getId());
        boolean targetOwner = viewerId != null && viewerId.equals(targetOwnerId);

        return new CommentResponse(
                comment.getId(),
                CommentAuthorResponse.from(comment.getUser()),
                comment.getContent(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                mine,
                mine || targetOwner
        );
    }
}
