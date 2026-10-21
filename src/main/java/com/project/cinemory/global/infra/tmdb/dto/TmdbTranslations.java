package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code append_to_response=translations}로 실려 오는 {@code translations} 객체.
 * 수십 개 언어가 {@code translations.translations[]}로 들어 있다 (tmdb-sync-spec 6-11, 실응답 확인).
 */
public record TmdbTranslations(List<Item> translations) {

    public record Item(
            @JsonProperty("iso_639_1") String iso6391,
            @JsonProperty("iso_3166_1") String iso31661,
            Data data
    ) {
    }

    public record Data(String overview) {
    }

    public List<Item> translationsOrEmpty() {
        return translations == null ? List.of() : translations;
    }
}
