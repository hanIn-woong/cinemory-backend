package com.project.cinemory.global.infra.tmdb.dto;

import java.util.List;

/**
 * {@code GET /movie/{id}?append_to_response=credits}로 함께 실려 오는 {@code credits} 객체.
 *
 * <p>상세와 크레딧을 1회 호출에 묶는 이유는 tmdb-sync-spec 6-2 참고 — 나눠 부르면
 * 영화당 왕복이 2배가 되고, 시드 적재에서 그대로 소요 시간이 된다.
 */
public record TmdbCredits(List<TmdbCast> cast, List<TmdbCrew> crew) {

    public List<TmdbCast> castOrEmpty() {
        return cast == null ? List.of() : cast;
    }

    public List<TmdbCrew> crewOrEmpty() {
        return crew == null ? List.of() : crew;
    }
}
