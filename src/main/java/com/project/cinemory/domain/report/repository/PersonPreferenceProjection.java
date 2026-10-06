package com.project.cinemory.domain.report.repository;

import java.math.BigDecimal;

/**
 * 누적 선호 배우·감독 TOP N — {@link PreferenceProjection} + {@code profilePath}(4-8-I).
 * 장르·국가 쿼리는 이 별칭을 SELECT하지 않으므로 projection을 공유하지 않는다.
 */
public interface PersonPreferenceProjection {
    Long getId();
    String getName();
    String getProfilePath();
    BigDecimal getScore();
    Long getCount();
}
