package com.project.cinemory.domain.wish.dto;

import com.project.cinemory.domain.movie.dto.CountryResponse;
import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.wish.entity.WishMovie;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WishListItemResponse(
        Long movieId,
        String title,
        String posterPath,
        LocalDate releaseDate,
        List<GenreResponse> genres,
        List<CountryResponse> countries,
        LocalDateTime addedAt
) {

    public static WishListItemResponse from(WishMovie wishMovie, List<GenreResponse> genres, List<CountryResponse> countries) {
        Movie movie = wishMovie.getMovie();
        return new WishListItemResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                genres,
                countries,
                wishMovie.getCreatedAt()
        );
    }
}
