package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /search/movie} 응답.
 *
 * <p>박스오피스 역방향 시드(6-5)는 첫 결과의 {@code id}만 쓰지만, 온디맨드 검색
 * suggestions(6-8)는 목록 표시용 {@code title}/{@code posterPath}/{@code releaseDate}까지 쓴다.
 */
public record TmdbSearchResponse(List<Item> results) {

    public record Item(
            Long id,
            String title,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("release_date") String releaseDate
    ) {

        /** {@code release_date}가 빈 문자열로 오는 경우가 있어 null로 정규화한다 (6-3과 동일 규칙). */
        public LocalDate parsedReleaseDate() {
            return (releaseDate == null || releaseDate.isBlank()) ? null : LocalDate.parse(releaseDate);
        }
    }

    /** 응답이 비어 오는 경우를 호출부가 매번 방어하지 않도록 빈 리스트로 정규화한다. */
    public List<Item> resultsOrEmpty() {
        return results == null ? List.of() : results;
    }
}
