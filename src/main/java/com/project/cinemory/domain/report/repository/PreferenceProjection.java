package com.project.cinemory.domain.report.repository;

import java.math.BigDecimal;

/** 선호 장르·국가·배우·감독 TOP N (2-4 패턴). {@code score} 정렬, {@code count}는 부제로 함께 반환(RA-4). */
public interface PreferenceProjection {
    Long getId();
    String getName();
    BigDecimal getScore();
    Long getCount();
}
