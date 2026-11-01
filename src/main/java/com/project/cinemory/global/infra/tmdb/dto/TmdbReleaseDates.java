package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code append_to_response=release_dates} 응답(tmdb-sync-spec 6-12-2). 국가별 개봉 정보 목록이고,
 * 우리는 {@code iso_3166_1 = "KR"} 항목의 {@code certification}만 쓴다.
 */
public record TmdbReleaseDates(List<Country> results) {

    public List<Country> resultsOrEmpty() {
        return results == null ? List.of() : results;
    }

    public record Country(
            @JsonProperty("iso_3166_1") String iso31661,
            @JsonProperty("release_dates") List<Release> releaseDates
    ) {
        public List<Release> releasesOrEmpty() {
            return releaseDates == null ? List.of() : releaseDates;
        }
    }

    /** {@code type}: 1 Premiere · 2 Theatrical(limited) · 3 Theatrical · 4 Digital · 5 Physical · 6 TV. */
    public record Release(String certification, Integer type) {
    }
}
