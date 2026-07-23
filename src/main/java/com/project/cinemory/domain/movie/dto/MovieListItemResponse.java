package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

import java.time.LocalDate;
import java.util.List;

public record MovieListItemResponse(
        Long id,
        String title,
        String posterPath,
        LocalDate releaseDate,
        List<GenreResponse> genres,
        List<CountryResponse> countries
) {

    public static MovieListItemResponse from(Movie movie, List<GenreResponse> genres, List<CountryResponse> countries) {
        return new MovieListItemResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                genres,
                countries
        );
    }
}
