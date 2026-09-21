package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.OldestWatchedProjection;

import java.time.LocalDate;

public record OldestWatchedResponse(Long movieId, String title, LocalDate releaseDate) {

    public static OldestWatchedResponse from(OldestWatchedProjection projection) {
        return new OldestWatchedResponse(projection.getMovieId(), projection.getTitle(), projection.getReleaseDate());
    }
}
