package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.MostWatchedProjection;
import com.project.cinemory.domain.report.repository.PreferenceProjection;

import java.math.BigDecimal;

/** 선호 장르·국가·배우·감독 TOP N 및 월말 "가장 많이 본 감독" 공용(RA-4). 후자는 score가 없다. */
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
