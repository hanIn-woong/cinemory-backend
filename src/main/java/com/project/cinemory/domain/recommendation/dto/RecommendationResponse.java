package com.project.cinemory.domain.recommendation.dto;

import com.project.cinemory.global.dto.PageResponse;

import java.util.List;

/**
 * 추천 응답 (controller-layer-spec 5-14-C) — 내 취향 추천과 자연어로 찾기가 같은 형태를 쓴다.
 *
 * @param personalized 프로필 전 축이 0이면 false (게스트 포함) — 앱이 "기록하거나 찜하면 취향 추천이 시작돼요"를 띄운다
 * @param conditions   자연어 전용, 취향 추천은 빈 배열
 * @param degraded     자연어 전용(Gemini 실패·타임아웃·벡터 인덱스 비어 있음 → 취향 추천 공식 결과로 대체), 취향 추천은 false
 */
public record RecommendationResponse(
        PageResponse<RecommendedMovieResponse> movies,
        boolean personalized,
        List<AppliedConditionResponse> conditions,
        boolean degraded) {
}
