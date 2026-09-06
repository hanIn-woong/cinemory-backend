package com.project.cinemory.global.infra.tmdb.dto;

import java.util.List;

/**
 * {@code GET /genre/movie/list} 응답.
 *
 * <p>{@code {"genres": [{"id": 28, "name": "액션"}, ...]}} 형태의 래퍼다.
 * 국가 목록({@link TmdbCountryListItem})이 루트 배열인 것과 다르니 혼동하지 말 것.
 */
public record TmdbGenreListResponse(List<Item> genres) {

    /**
     * @param id   TMDB 장르 ID — {@code genre.tmdb_genre_id}에 대응하는 자연키
     * @param name {@code language=ko-KR} 기준 장르명
     */
    public record Item(Integer id, String name) {
    }

    /** 응답이 비어 오는 경우를 호출부가 매번 방어하지 않도록 빈 리스트로 정규화한다. */
    public List<Item> items() {
        return genres == null ? List.of() : genres;
    }
}
