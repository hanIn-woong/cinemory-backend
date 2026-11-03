package com.project.cinemory.domain.recommendation.dto;

import java.util.List;

/**
 * 내 취향 추천 묶음 (controller-layer-spec 5-14-K, M3b-recommendation-spec R-5-3).
 *
 * <p>페이징 DTO가 아니다 - 묶음은 매번 다른 표본이라 {@code PageResponse} 계약(같은 page = 같은 결과)이 성립하지 않는다.
 *
 * @param movies       최대 10편, 묶음 안은 점수(S) 내림차순
 * @param personalized 프로필 전 축이 0이면 false (게스트 포함) - 앱이 "기록하거나 찜하면 취향 추천이 시작돼요"를 띄운다
 * @param hasMore      풀에 아직 안 본(최근 묶음에 없는) 영화가 남았는가. false여도 "다른 추천 보기"는 가능하다(오래된 묶음의 제외가 풀린다)
 */
public record ForYouBatchResponse(
        List<RecommendedMovieResponse> movies,
        boolean personalized,
        boolean hasMore) {
}
