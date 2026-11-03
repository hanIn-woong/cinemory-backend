package com.project.cinemory.domain.recommendation.dto;

import com.project.cinemory.domain.movie.entity.RecommendOverride;

/**
 * 추천 제외 덮어쓰기 변경 요청 (controller-layer-spec 5-14-K).
 *
 * @param override {@code BLOCK}(추천에서 제외) · {@code ALLOW}(자동 판정이 막아도 허용) · {@code null}(덮어쓰기 해제 - 자동 판정을 따른다)
 */
public record RecommendOverrideRequest(RecommendOverride override) {
}
