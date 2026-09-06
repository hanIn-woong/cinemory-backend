package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /movie/{id}}의 {@code production_countries[]} 항목.
 *
 * <p>{@code {iso_3166_1, name}} 구조라 6-1의 {@link TmdbCountryListItem}
 * ({@code {iso_3166_1, english_name, native_name}})과 구조가 달라 별도 DTO로 둔다
 * (tmdb-sync-spec 6-3 ⑨).
 *
 * @param iso31661 ISO 3166-1 alpha-2 코드 — {@code country.code}에 대응하는 자연키
 * @param name     TMDB가 내려주는 국가명 (참조 테이블 upsert에는 쓰지 않는다 — 대표국 판정에만 사용)
 */
public record TmdbProductionCountry(
        @JsonProperty("iso_3166_1") String iso31661,
        String name
) {
}
