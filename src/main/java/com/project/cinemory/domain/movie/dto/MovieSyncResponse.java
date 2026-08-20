package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

public record MovieSyncResponse(Long movieId) {

    public static MovieSyncResponse from(Movie movie) {
        return new MovieSyncResponse(movie.getId());
    }
}
