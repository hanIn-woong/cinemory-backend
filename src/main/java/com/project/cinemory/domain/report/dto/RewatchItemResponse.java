package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.RewatchProjection;

public record RewatchItemResponse(Long movieId, String title, String posterPath, long watchCount) {

    public static RewatchItemResponse from(RewatchProjection projection) {
        return new RewatchItemResponse(projection.getMovieId(), projection.getTitle(),
                projection.getPosterPath(), projection.getWatchCount());
    }
}
