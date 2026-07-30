package com.project.cinemory.domain.collection.dto;

import com.project.cinemory.domain.collection.entity.Collection;

import java.time.LocalDateTime;

public record CollectionResponse(
        Long id,
        String name,
        String description,
        long movieCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CollectionResponse from(Collection collection, long movieCount) {
        return new CollectionResponse(
                collection.getId(),
                collection.getName(),
                collection.getDescription(),
                movieCount,
                collection.getCreatedAt(),
                collection.getUpdatedAt()
        );
    }
}
