package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code credits.cast[]} 항목.
 *
 * <p>⚠️ {@code id}와 {@code cast_id}를 혼동하지 말 것 (tmdb-sync-spec 6-3 ⑤). {@code id}가
 * <b>사람 ID</b>({@code person.tmdb_person_id}에 대응)이고, {@code cast_id}는 <b>이 크레딧만의
 * ID</b>다. 후자를 {@code Person.tmdbPersonId}에 넣으면 {@code uk_person_tmdb_id}가 엉뚱한
 * 값으로 잡히고 예외 없이 잘못된 인물 데이터가 쌓인다. 이 DTO는 그 위험을 없애기 위해
 * {@code cast_id}를 아예 필드로 두지 않는다.
 *
 * @param id           TMDB 인물 ID
 * @param name         {@code language=ko-KR} 기준 배우명 (비어 올 수 있음)
 * @param originalName 원어 배우명 — {@code name} 폴백용
 * @param profilePath  프로필 이미지 경로
 * @param character    배역명 — 다역은 TMDB가 {@code "Character 1 / Character 2"}로 슬래시 연결
 * @param order        빌링 순서 원본값. {@code MovieActor.displayOrder}에 그대로 저장한다.
 *                     연속 정수라는 보장이 없으므로 배열 인덱스가 아니라 이 값을 쓴다
 */
public record TmdbCast(
        Long id,
        String name,
        @JsonProperty("original_name") String originalName,
        @JsonProperty("profile_path") String profilePath,
        String character,
        Integer order
) {

    /** {@code name}이 비어 오는 경우 원어명으로 폴백한다 (person.name not null, 6-3 ⑦). */
    public String resolveName() {
        return (name == null || name.isBlank()) ? originalName : name;
    }

    /** {@link #resolveName()}이 폴백을 탔는지 — 발동 빈도 계측용(잔여 #4). */
    public boolean isNameFallback() {
        return name == null || name.isBlank();
    }
}
