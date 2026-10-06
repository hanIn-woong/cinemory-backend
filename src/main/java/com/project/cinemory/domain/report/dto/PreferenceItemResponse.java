package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.MostWatchedProjection;
import com.project.cinemory.domain.report.repository.PreferenceProjection;

import java.math.BigDecimal;

/**
 * 선호 장르·국가 TOP N 및 기간 리포트 "많이 본 장르·국가" 공용(RA-4). 후자는 score가 없다.
 * 인물(배우·감독)은 2026-10-07부터 {@link PersonRankItemResponse}(4-8-I).
 */
public record PreferenceItemResponse(Long id, String name, BigDecimal score, Long count) {

    public static PreferenceItemResponse from(PreferenceProjection projection) {
        return new PreferenceItemResponse(projection.getId(), projection.getName(),
                projection.getScore(), projection.getCount());
    }

    public static PreferenceItemResponse from(MostWatchedProjection projection) {
        return new PreferenceItemResponse(projection.getId(), projection.getName(),
                null, projection.getCount());
    }
}
