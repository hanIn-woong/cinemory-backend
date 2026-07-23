package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.genre.entity.Genre;

public record GenreResponse(Long id, String name) {

    public static GenreResponse from(Genre genre) {
        return new GenreResponse(genre.getId(), genre.getName());
    }
}
