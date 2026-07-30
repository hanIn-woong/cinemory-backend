package com.project.cinemory.domain.comment.service;

import com.project.cinemory.domain.comment.dto.CommentCreateRequest;
import com.project.cinemory.domain.comment.dto.CommentResponse;
import com.project.cinemory.domain.comment.entity.Comment;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.comment.repository.CommentRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final UserAccessPolicy userAccessPolicy;
    private final Map<TargetType, CommentTargetResolver> resolvers;

    /**
     * {@code Map<TargetType, CommentTargetResolver>}를 직접 주입받지 않는 이유:
     * Spring의 Map 자동 주입은 키가 <b>빈 이름(String)</b>이라 TargetType 키로 쓸 수 없다.
     * List로 주입받아 생성자에서 변환하면 필드를 final로 유지할 수 있다.
     */
    public CommentService(CommentRepository commentRepository,
                          UserRepository userRepository,
                          UserAccessPolicy userAccessPolicy,
                          List<CommentTargetResolver> resolvers) {
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.userAccessPolicy = userAccessPolicy;
        this.resolvers = new EnumMap<>(TargetType.class);
        resolvers.forEach(resolver -> this.resolvers.put(resolver.supports(), resolver));
    }

    @Transactional
    public CommentResponse createComment(Long authorId, CommentCreateRequest request) {
        requireAuthenticated(authorId);

        // 대상 소유자 조회 = 대상 존재 검증 (1쿼리)
        Long targetOwnerId = resolveOwnerId(request.targetType(), request.targetId());
        // 비공개 대상에는 댓글을 달 수 없다 — 대상 소유자의 공개범위를 그대로 따른다
        userAccessPolicy.validateCanView(authorId, targetOwnerId);

        Comment comment = Comment.builder()
                .user(userRepository.getReferenceById(authorId))
                .targetType(request.targetType())
                .targetId(request.targetId())
                .content(request.content())
                .build();
        commentRepository.save(comment);

        return CommentResponse.from(comment, authorId, targetOwnerId);
    }

    /** 댓글 목록. 고정 쿼리: 소유자 1 + 접근판정 + count 1 + 본문 1 */
    public Page<CommentResponse> getComments(Long viewerId, TargetType targetType, Long targetId, Pageable pageable) {
        Long targetOwnerId = resolveOwnerId(targetType, targetId);
        userAccessPolicy.validateCanView(viewerId, targetOwnerId);

        return commentRepository.findByTargetTypeAndTargetIdOrderByIdAsc(targetType, targetId, pageable)
                .map(comment -> CommentResponse.from(comment, viewerId, targetOwnerId));
    }

    /** 수정은 작성자 본인만 — 타인이 남의 글 내용을 바꾸는 것은 위조에 해당한다. */
    @Transactional
    public CommentResponse editComment(Long viewerId, Long commentId, String content) {
        requireAuthenticated(viewerId);

        Comment comment = findCommentOrThrow(commentId);
        if (!isAuthor(comment, viewerId)) {
            throw new BusinessException(ErrorCode.NO_COMMENT_PERMISSION);
        }

        comment.editContent(content); // dirty checking으로 반영 (save() 재호출 금지)

        Long targetOwnerId = resolveOwnerId(comment.getTargetType(), comment.getTargetId());
        return CommentResponse.from(comment, viewerId, targetOwnerId);
    }

    /**
     * 삭제는 작성자 본인 또는 대상(컬렉션/리뷰) 소유자.
     * 작성자 판정을 먼저 하므로 "내 댓글 삭제"라는 일반적인 경우에는 resolver 쿼리가 발생하지 않는다.
     */
    @Transactional
    public void deleteComment(Long viewerId, Long commentId) {
        requireAuthenticated(viewerId);

        Comment comment = findCommentOrThrow(commentId);
        if (!isAuthor(comment, viewerId) && !isTargetOwner(comment, viewerId)) {
            throw new BusinessException(ErrorCode.NO_COMMENT_PERMISSION);
        }

        commentRepository.delete(comment);
    }

    private Comment findCommentOrThrow(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
    }

    private boolean isAuthor(Comment comment, Long viewerId) {
        return comment.getUser().getId().equals(viewerId);
    }

    private boolean isTargetOwner(Comment comment, Long viewerId) {
        return resolveOwnerId(comment.getTargetType(), comment.getTargetId()).equals(viewerId);
    }

    /**
     * TargetType에 대응하는 resolver가 없으면 즉시 실패시켜,
     * ENUM 확장 시 구현체 누락을 런타임에 바로 드러낸다.
     */
    private Long resolveOwnerId(TargetType targetType, Long targetId) {
        CommentTargetResolver resolver = resolvers.get(targetType);
        if (resolver == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_COMMENT_TARGET);
        }
        return resolver.findOwnerIdOrThrow(targetId);
    }

    private void requireAuthenticated(Long viewerId) {
        if (viewerId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
