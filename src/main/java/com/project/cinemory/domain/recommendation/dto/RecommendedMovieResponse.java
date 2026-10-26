package com.project.cinemory.domain.recommendation.dto;

import com.project.cinemory.domain.movie.dto.GenreResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 추천 카드 한 장 (controller-layer-spec 5-14-C). 두 추천이 같은 카드를 쓴다.
 *
 * @param title    표시 제목({@code COALESCE(title_ko, title)})
 * @param genres   {@code id} 순
 * @param reason   null 가능 — 자연어에서 {@code sim}이 최대 기여일 때
 * @param wished   자연어 전용. 취향 추천은 항상 false
 */
public record RecommendedMovieResponse(
        Long id,
        String title,
        String posterPath,
        LocalDate releaseDate,
        List<GenreResponse> genres,
        RecommendReasonResponse reason,
        boolean wished) {
}
