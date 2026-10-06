package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.PersonMostWatchedProjection;
import com.project.cinemory.domain.report.repository.PersonPreferenceProjection;

import java.math.BigDecimal;

/** 리포트의 인물 항목 전용(4-8-I). 장르·국가는 {@link PreferenceItemResponse}를 그대로 쓴다. 기간 리포트는 score가 없다. */
public record PersonRankItemResponse(Long id, String name, String profilePath, BigDecimal score, Long count) {

    public static PersonRankItemResponse from(PersonPreferenceProjection projection) {
        return new PersonRankItemResponse(projection.getId(), projection.getName(), projection.getProfilePath(),
                projection.getScore(), projection.getCount());
    }

    public static PersonRankItemResponse from(PersonMostWatchedProjection projection) {
        return new PersonRankItemResponse(projection.getId(), projection.getName(), projection.getProfilePath(),
                null, projection.getCount());
    }
}
