package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.FiveStarMovieProjection;

import java.time.LocalDate;

/** 연간 "올해 5점을 준 작품" 항목(M3a 10-4). 목록형 — 그해 5점 회차가 하나라도 있으면 포함된다. */
public record FiveStarMovieResponse(Long movieId, String title, String posterPath, LocalDate fiveStarDate) {

    public static FiveStarMovieResponse from(FiveStarMovieProjection projection) {
        return new FiveStarMovieResponse(projection.getMovieId(), projection.getTitle(),
                projection.getPosterPath(), projection.getFiveStarDate());
    }
}
