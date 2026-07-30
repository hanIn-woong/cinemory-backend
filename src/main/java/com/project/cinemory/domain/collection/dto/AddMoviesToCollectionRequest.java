package com.project.cinemory.domain.collection.dto;

import java.util.List;

public record AddMoviesToCollectionRequest(List<Long> movieIds) {
}
