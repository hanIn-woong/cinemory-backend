package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code credits.crew[]} 항목.
 *
 * <p>감독 판정은 {@code department == "Directing"}이 아니라 <b>{@code job == "Director"}</b>로
 * 정확히 건다 — {@code department}로 거르면 조감독·스크립트 등이 함께 들어온다
 * (tmdb-sync-spec 6-3, `MovieDirector`절).
 *
 * @param id           TMDB 인물 ID
 * @param name         {@code language=ko-KR} 기준 인물명 (비어 올 수 있음)
 * @param originalName 원어 인물명 — {@code name} 폴백용
 * @param profilePath  프로필 이미지 경로
 * @param job          {@code "Director"} 여부 판정에 쓴다
 */
public record TmdbCrew(
        Long id,
        String name,
        @JsonProperty("original_name") String originalName,
        @JsonProperty("profile_path") String profilePath,
        String job
) {

    public String resolveName() {
        return (name == null || name.isBlank()) ? originalName : name;
    }

    public boolean isDirector() {
        return "Director".equals(job);
    }
}
