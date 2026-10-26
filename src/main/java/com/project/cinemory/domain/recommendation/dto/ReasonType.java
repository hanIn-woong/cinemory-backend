package com.project.cinemory.domain.recommendation.dto;

/** 추천 이유 종류 (controller-layer-spec 5-14-C). 추가·변경은 API 계약 변경이다. */
public enum ReasonType {
    DIRECTOR, ACTOR, GENRE, COUNTRY,              // 평가 기반 (S-1-5)
    WISH_GENRE, WISH_COUNTRY, WISH_DIRECTOR,      // 찜 관심 (W-1)
    QUALITY, POPULARITY                           // targetId·label = null
}
