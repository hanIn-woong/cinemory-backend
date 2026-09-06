package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.service.ResyncResult;

public record MovieResyncResponse(int updated, int skipped, boolean stoppedByRateLimit, Long lastProcessedId) {

    public static MovieResyncResponse from(ResyncResult result) {
        return new MovieResyncResponse(result.updated(), result.skipped(), result.stoppedByRateLimit(), result.lastProcessedId());
    }
}
