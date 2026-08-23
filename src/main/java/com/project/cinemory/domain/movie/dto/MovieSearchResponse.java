package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.global.dto.PageResponse;

import java.util.List;

/**
 * {@code GET /api/movies/search} 응답 (tmdb-sync-spec 6-8).
 *
 * <p><b>두 집합을 섞지 않는다.</b> 섞으면 {@code totalElements}를 계산할 수 없다 — DB와
 * TMDB를 합쳐 몇 건인지 알려면 겹치는 수를 알아야 하고, 그건 TMDB 전체를 받아야만 나온다.
 *
 * @param registered  우리 DB 검색 결과. 완전한 {@code PageResponse}라 5-0 규약 예외가 없다
 * @param suggestions 아직 등록되지 않은 TMDB 검색 결과. {@code page == 1}에서만 채워지고
 *                    페이징이 없다(상위 N개)
 */
public record MovieSearchResponse(
        PageResponse<MovieSummaryResponse> registered,
        List<MovieSearchSuggestionResponse> suggestions
) {
}
