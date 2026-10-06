package com.project.cinemory.domain.movie.dto;

import java.math.BigDecimal;

/**
 * TMDB · CineMory 공용 평점 요약 (service-layer-spec 4-2-A).
 * count == 0이면 average는 항상 null이다 — "0.0점"으로 보이지 않게 한다.
 */
public record RatingSummary(BigDecimal average, long count) {

    public static RatingSummary of(BigDecimal average, Number count) {
        long safeCount = (count == null) ? 0L : count.longValue();
        return new RatingSummary(safeCount == 0 ? null : average, safeCount);
    }
}
