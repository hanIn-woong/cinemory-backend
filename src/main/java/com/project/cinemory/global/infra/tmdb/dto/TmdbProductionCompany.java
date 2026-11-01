package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /movie/{id}}의 {@code production_companies[]} 항목(tmdb-sync-spec 6-12-3). */
public record TmdbProductionCompany(
        Long id,
        String name,
        @JsonProperty("logo_path") String logoPath,
        @JsonProperty("origin_country") String originCountry
) {
}
