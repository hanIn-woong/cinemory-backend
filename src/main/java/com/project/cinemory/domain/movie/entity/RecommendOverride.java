package com.project.cinemory.domain.movie.entity;

/**
 * 관리자의 추천 제외 덮어쓰기 (M3b-recommendation-spec R-5-1). {@code movie.recommend_override}가 {@code null}이면 자동 판정을 따른다.
 * 추천에서만 빠지거나 허용될 뿐 영화 검색·상세·기록에는 영향이 없다.
 */
public enum RecommendOverride {
    /** 자동 판정과 무관하게 추천에서 제외한다. */
    BLOCK,
    /** 자동 판정이 막아도 추천에 허용한다. */
    ALLOW
}
