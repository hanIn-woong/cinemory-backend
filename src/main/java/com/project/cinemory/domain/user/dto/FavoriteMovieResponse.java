package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.movie.entity.Movie;

/** 프로필의 인생 영화 한 편 (profile-spec 3-4). {@code title}은 화면에 보이는 제목({@code displayTitle()}). 이미지 경로는 TMDB 경로 그대로 — 앱이 URL을 조립한다. */
public record FavoriteMovieResponse(Long movieId, String title, String posterPath, String backdropPath) {

    public static FavoriteMovieResponse from(Movie movie) {
        return new FavoriteMovieResponse(movie.getId(), movie.displayTitle(), movie.getPosterPath(), movie.getBackdropPath());
    }
}
