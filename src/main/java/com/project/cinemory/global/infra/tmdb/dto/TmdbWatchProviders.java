package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /movie/{id}/watch/providers} 응답(service-layer-spec 4-2-B ③). {@code results}는 국가 코드 → 지역 항목이고
 * 우리는 {@code KR}만 읽는다. 데이터 원천은 JustWatch이며 화면에 출처 표기가 의무다.
 */
public record TmdbWatchProviders(Map<String, Region> results) {

    public Region regionOrNull(String countryCode) {
        return results == null ? null : results.get(countryCode);
    }

    public record Region(String link, List<Provider> flatrate, List<Provider> rent, List<Provider> buy) {
    }

    public record Provider(
            @JsonProperty("provider_id") Long providerId,
            @JsonProperty("provider_name") String providerName,
            @JsonProperty("logo_path") String logoPath,
            @JsonProperty("display_priority") Integer displayPriority
    ) {
    }
}
