package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 관리자 영화 목록 행(admin-spec 3-4). {@code posterPath}는 TMDB 경로 그대로 — base URL 조립은 관리자 웹 몫이다. */
public record AdminMovieSummaryResponse(
        Long movieId,
        Long tmdbId,
        String koficMovieCd,
        String title,
        String originalTitle,
        LocalDate releaseDate,
        String posterPath,
        BigDecimal voteAverage,
        Integer voteCount,
        LocalDateTime createdAt
) {

    public static AdminMovieSummaryResponse from(Movie movie) {
        return new AdminMovieSummaryResponse(movie.getId(), movie.getTmdbId(), movie.getKoficMovieCd(),
                movie.getTitle(), movie.getOriginalTitle(), movie.getReleaseDate(), movie.getPosterPath(),
                movie.getVoteAverage(), movie.getVoteCount(), movie.getCreatedAt());
    }
}
