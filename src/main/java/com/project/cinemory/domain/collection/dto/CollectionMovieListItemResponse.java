package com.project.cinemory.domain.collection.dto;

import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.movie.entity.Movie;

public record CollectionMovieListItemResponse(
        Long movieId,
        String posterPath,
        String title,
        Integer releaseYear,
        String directorNames
) {

    public static CollectionMovieListItemResponse from(CollectionMovie collectionMovie, String directorNames) {
        Movie movie = collectionMovie.getMovie();
        Integer releaseYear = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : null;
        return new CollectionMovieListItemResponse(
                movie.getId(),
                movie.getPosterPath(),
                movie.getTitle(),
                releaseYear,
                directorNames
        );
    }
}
