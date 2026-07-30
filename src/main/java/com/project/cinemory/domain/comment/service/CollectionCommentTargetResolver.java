package com.project.cinemory.domain.comment.service;

import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CollectionCommentTargetResolver implements CommentTargetResolver {

    private final CollectionRepository collectionRepository;

    @Override
    public TargetType supports() {
        return TargetType.COLLECTION;
    }

    @Override
    public Long findOwnerIdOrThrow(Long targetId) {
        return collectionRepository.findOwnerIdById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_NOT_FOUND));
    }
}
