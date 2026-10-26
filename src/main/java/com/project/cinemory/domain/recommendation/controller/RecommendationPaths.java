package com.project.cinemory.domain.recommendation.controller;

/**
 * 추천 API 경로 상수 — {@code SecurityConfig}와 Controller가 <b>같은 값을 공유</b>한다 (controller-layer-spec 5-0-F · 5-14-A).
 * 화이트리스트와 매핑이 따로 적히면 한쪽만 바뀌었을 때 조용히 갈라진다.
 *
 * <p>⚠️ 공개(permitAll)는 {@link #FOR_YOU} <b>정확한 경로 하나</b>다. {@link #SEARCH}는 외부 비용이 드는 로그인 전용 경로라
 * {@code /api/recommendations/**}로 넓히면 안 된다.
 */
public final class RecommendationPaths {

    private RecommendationPaths() {
    }

    /** 내 취향 추천 — 비로그인 허용(게스트 = 콜드 스타트 결과). */
    public static final String FOR_YOU = "/api/recommendations/for-you";

    /** 자연어로 찾기 — 로그인 필수(외부 비용 · 사용자별 호출 제한 · 본 영화 제외). */
    public static final String SEARCH = "/api/recommendations/search";
}
