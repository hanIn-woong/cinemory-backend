package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

import java.time.LocalDate;

public record MovieSummaryResponse(Long id, String title, String posterPath, LocalDate releaseDate) {

    public static MovieSummaryResponse from(Movie movie) {
        return new MovieSummaryResponse(movie.getId(), movie.getTitle(), movie.getPosterPath(), movie.getReleaseDate());
    }
}
