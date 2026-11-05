package com.project.cinemory.domain.movie.dto;

/** 포스터 고정 해제 결과(admin-spec 18-4). {@code resynced=false}면 고정만 풀렸고 TMDB 기본 포스터는 다음 동기화 때 돌아온다. */
public record AdminMoviePosterUnpinResponse(AdminMovieDetailResponse movie, boolean resynced) {
}
