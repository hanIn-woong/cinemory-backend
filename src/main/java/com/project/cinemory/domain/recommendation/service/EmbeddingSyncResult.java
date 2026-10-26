package com.project.cinemory.domain.recommendation.service;

/**
 * 임베딩 배치 1회의 결과 (M3b-recommendation-spec E-4-3).
 *
 * @param embedded           새로 임베딩해 저장한 영화 수
 * @param unchanged          해시가 같아 건너뛴 영화 수
 * @param failed             개별 실패로 건너뛴 영화 수
 * @param stoppedByRateLimit 429로 중도 중단됐는지
 * @param lastProcessedId    이어받기 커서. 다음 호출의 {@code afterId}로 넘긴다.
 *                           ⚠️ 429로 중단된 시점에 처리되지 못한 영화는 반영하지 않는다 — 반영하면 재개 시 그 영화를 건너뛴다.
 */
public record EmbeddingSyncResult(int embedded, int unchanged, int failed,
                                  boolean stoppedByRateLimit, Long lastProcessedId) {
}
