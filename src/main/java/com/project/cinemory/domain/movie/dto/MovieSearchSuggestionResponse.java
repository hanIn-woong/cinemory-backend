package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.global.infra.tmdb.dto.TmdbSearchResponse;

import java.time.LocalDate;

/**
 * 영화 검색의 {@code suggestions} 섹션 항목 (tmdb-sync-spec 6-8) — 아직 우리 DB에 없는
 * TMDB 검색 결과. {@code registered}(DB, {@link MovieSummaryResponse})와 섞이지 않는다 —
 * 식별자가 {@code movieId}가 아니라 {@code tmdbId}뿐이다.
 */
public record MovieSearchSuggestionResponse(Long tmdbId, String title, String posterPath, LocalDate releaseDate) {

    public static MovieSearchSuggestionResponse from(TmdbSearchResponse.Item item) {
        return new MovieSearchSuggestionResponse(item.id(), item.title(), item.posterPath(), item.parsedReleaseDate());
    }
}
