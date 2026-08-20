package com.project.cinemory.global.infra.tmdb.dto;

import java.util.List;

/**
 * {@code GET /search/movie} 응답.
 *
 * <p>온디맨드 동기화·박스오피스 역방향 시드(6-5)가 첫 결과의 {@code id}만 쓰므로
 * 항목 DTO는 {@code id}만 담는다.
 */
public record TmdbSearchResponse(List<Item> results) {

    public record Item(Long id) {
    }

    /** 응답이 비어 오는 경우를 호출부가 매번 방어하지 않도록 빈 리스트로 정규화한다. */
    public List<Item> resultsOrEmpty() {
        return results == null ? List.of() : results;
    }
}
