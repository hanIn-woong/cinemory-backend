package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieImagesResponse;

import java.util.List;

/** 관리자 포스터 후보(admin-spec 18-2). {@code current}는 지금 표시 중인 경로, {@code pinned}는 고정 값(없으면 null). */
public record AdminMoviePostersResponse(String current, String pinned, List<Candidate> candidates) {

    public record Candidate(String filePath, String language, Integer width, Integer height, Double voteAverage) {

        public static Candidate from(TmdbMovieImagesResponse.Poster poster) {
            return new Candidate(poster.filePath(), poster.language(), poster.width(), poster.height(), poster.voteAverage());
        }
    }
}
