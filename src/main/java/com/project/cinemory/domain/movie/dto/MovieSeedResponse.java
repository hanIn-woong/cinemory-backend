package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.service.SeedResult;

public record MovieSeedResponse(int matched, int skipped, int alreadyExists, boolean stoppedByRateLimit) {

    public static MovieSeedResponse from(SeedResult result) {
        return new MovieSeedResponse(result.matched(), result.skipped(), result.alreadyExists(), result.stoppedByRateLimit());
    }
}
