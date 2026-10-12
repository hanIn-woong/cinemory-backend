package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.OldestWatchedProjection;

import java.time.LocalDate;

// posterPath — 앱이 포스터 썸네일 + 상세 이동 행으로 그린다(2026-10-12, MovieRatingGapResponse·RewatchItemResponse와 같은 모양).
public record OldestWatchedResponse(Long movieId, String title, String posterPath, LocalDate releaseDate) {

    public static OldestWatchedResponse from(OldestWatchedProjection projection) {
        return new OldestWatchedResponse(projection.getMovieId(), projection.getTitle(), projection.getPosterPath(),
                projection.getReleaseDate());
    }
}
