package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

/** 자동 완성 항목(service-layer-spec 4-2-C ②). {@code year}는 개봉일이 없으면 null. */
public record MovieAutocompleteResponse(Long movieId, String title, Integer year, String posterPath) {

    public static MovieAutocompleteResponse from(Movie movie) {
        return new MovieAutocompleteResponse(movie.getId(), movie.displayTitle(),
                movie.getReleaseDate() == null ? null : movie.getReleaseDate().getYear(), movie.getPosterPath());
    }
}
