package com.project.cinemory.domain.comment.service;

import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewCommentTargetResolver implements CommentTargetResolver {

    private final ReviewRepository reviewRepository;

    @Override
    public TargetType supports() {
        return TargetType.REVIEW;
    }

    @Override
    public Long findOwnerIdOrThrow(Long targetId) {
        return reviewRepository.findOwnerIdById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
    }
}
