package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /discover/movie} 응답 (시드 보충 경로, 6-5).
 *
 * <p>{@code totalPages}로 호출부가 존재하지 않는 페이지를 두드리지 않게 한다.
 */
public record TmdbDiscoverResponse(List<Item> results, @JsonProperty("total_pages") Integer totalPages) {

    public record Item(Long id) {
    }

    public List<Item> resultsOrEmpty() {
        return results == null ? List.of() : results;
    }

    public int totalPagesOrZero() {
        return totalPages == null ? 0 : totalPages;
    }
}
