package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.PersonMostWatchedProjection;
import com.project.cinemory.domain.report.repository.PersonPreferenceProjection;

import java.math.BigDecimal;

/**
 * 리포트의 인물 항목 전용(4-8-I). 장르·국가는 {@link PreferenceItemResponse}를 그대로 쓴다.
 * 기간 리포트(편수형)는 score·averageRating이 없다. 누적의 score는 순위 근거일 뿐 별점 단위가 아니고, 화면 별점은 averageRating(4-8-K).
 */
public record PersonRankItemResponse(Long id, String name, String profilePath, BigDecimal score,
                                     BigDecimal averageRating, Long count) {

    public static PersonRankItemResponse from(PersonPreferenceProjection projection) {
        return new PersonRankItemResponse(projection.getId(), projection.getName(), projection.getProfilePath(),
                projection.getScore(), projection.getAverageRating(), projection.getCount());
    }

    public static PersonRankItemResponse from(PersonMostWatchedProjection projection) {
        return new PersonRankItemResponse(projection.getId(), projection.getName(), projection.getProfilePath(),
                null, null, projection.getCount());
    }
}
