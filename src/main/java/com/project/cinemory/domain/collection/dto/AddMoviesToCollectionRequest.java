package com.project.cinemory.domain.collection.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddMoviesToCollectionRequest(

        // 상한은 findAllById/findByCollectionIdAndMovieIdIn의 IN절 크기를 제한하기 위함이다(5-0 max-page-size와 동일 성격의 방어).
        @NotEmpty(message = "추가할 영화 목록은 비어 있을 수 없습니다.")
        @Size(max = 50, message = "한 번에 최대 50개까지 추가할 수 있습니다.")
        List<Long> movieIds
) {
}
