package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.validation.constraints.Size;

/**
 * 영화 한글 제목 변경 요청(admin-spec 12-B-3). {@code null}·빈 문자열 = 해제. 255자 초과는 400.
 */
public record AdminMovieTitleKoRequest(
        @Size(max = Movie.TITLE_KO_MAX_LENGTH, message = "한글 제목은 255자를 넘을 수 없습니다.")
        String titleKo
) {
}
