package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

import java.time.LocalDate;
import java.util.List;

public record MovieDetailResponse(
        Long id,
        Long tmdbId,
        String koficMovieCd,
        String title,
        String posterPath,
        LocalDate releaseDate,
        String overview,
        Integer runtime,
        List<GenreResponse> genres,
        List<CountryResponse> countries,
        List<ActorResponse> actors,
        List<DirectorResponse> directors
) {

    public static MovieDetailResponse from(Movie movie, List<GenreResponse> genres, List<CountryResponse> countries,
                                            List<ActorResponse> actors, List<DirectorResponse> directors) {
        return new MovieDetailResponse(
                movie.getId(),
                movie.getTmdbId(),
                movie.getKoficMovieCd(),
                movie.getTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                movie.getOverview(),
                movie.getRuntime(),
                genres,
                countries,
                actors,
                directors
        );
    }
}
