package com.project.cinemory.domain.person.dto;

import com.project.cinemory.domain.person.entity.Person;

/**
 * 관리자 인물 목록 행(admin-spec 12-3). {@code name}은 TMDB 원본, {@code nameKo}는 관리자 입력(없으면 null) —
 * 앱에 나가는 표시 이름({@code displayName})과 달리 둘을 따로 싣는다.
 *
 * @param creditCount 출연 + 연출 수
 */
public record AdminPersonSummaryResponse(
        Long personId,
        Long tmdbPersonId,
        String name,
        String nameKo,
        String profilePath,
        long creditCount
) {

    public static AdminPersonSummaryResponse of(Person person, long creditCount) {
        return new AdminPersonSummaryResponse(person.getId(), person.getTmdbPersonId(), person.getName(),
                person.getNameKo(), person.getProfilePath(), creditCount);
    }
}
