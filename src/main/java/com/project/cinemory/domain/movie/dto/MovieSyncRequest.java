package com.project.cinemory.domain.movie.dto;

import jakarta.validation.constraints.NotNull;

public record MovieSyncRequest(

        @NotNull(message = "tmdbId는 필수입니다.")
        Long tmdbId
) {
}
