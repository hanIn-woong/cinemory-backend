package com.project.cinemory.domain.person.dto;

import com.project.cinemory.domain.person.entity.Person;
import jakarta.validation.constraints.Size;

/**
 * 인물 한글명 변경 요청(admin-spec 12-3). {@code null}·빈 문자열 = 해제. 100자 초과는 400.
 */
public record AdminPersonNameKoRequest(
        @Size(max = Person.NAME_KO_MAX_LENGTH, message = "한글명은 100자를 넘을 수 없습니다.")
        String nameKo
) {
}
