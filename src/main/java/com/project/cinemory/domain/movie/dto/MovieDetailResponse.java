package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

import java.time.LocalDate;
import java.util.List;

public record MovieDetailResponse(
        Long id,
        Long tmdbId,
        String koficMovieCd,
        String title,
        // 원제(nullable, service-layer-spec 4-2 · 2026-11-01). `title`은 한글 제목이 있으면 그것이라, 두 값이 다를 때만 화면에 보인다.
        String originalTitle,
        String posterPath,
        LocalDate releaseDate,
        String overview,
        Integer runtime,
        List<GenreResponse> genres,
        List<CountryResponse> countries,
        List<ActorResponse> actors,
        List<DirectorResponse> directors,
        MovieRatingsResponse ratings
) {

    public static MovieDetailResponse from(Movie movie, List<GenreResponse> genres, List<CountryResponse> countries,
                                            List<ActorResponse> actors, List<DirectorResponse> directors,
                                            MovieRatingsResponse ratings) {
        return new MovieDetailResponse(
                movie.getId(),
                movie.getTmdbId(),
                movie.getKoficMovieCd(),
                movie.displayTitle(),
                movie.getOriginalTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                movie.getOverview(),
                movie.getRuntime(),
                genres,
                countries,
                actors,
                directors,
                ratings
        );
    }
}
