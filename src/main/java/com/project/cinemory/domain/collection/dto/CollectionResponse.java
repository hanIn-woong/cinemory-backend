package com.project.cinemory.domain.collection.dto;

import com.project.cinemory.domain.collection.entity.Collection;

import java.time.LocalDateTime;
import java.util.List;

public record CollectionResponse(
        Long id,
        String name,
        String description,
        long movieCount,
        // 최대 5장, position ASC. 포스터 없는 영화는 빠지므로 movieCount보다 적을 수 있다(5-4-A ③)
        List<String> previewPosterPaths,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CollectionResponse from(Collection collection, long movieCount, List<String> previewPosterPaths) {
        return new CollectionResponse(
                collection.getId(),
                collection.getName(),
                collection.getDescription(),
                movieCount,
                previewPosterPaths,
                collection.getCreatedAt(),
                collection.getUpdatedAt()
        );
    }
}
