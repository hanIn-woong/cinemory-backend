package com.project.cinemory.domain.movie.service;

/**
 * {@link MovieSeedService#resync}의 결과 (tmdb-sync-spec 6-9).
 *
 * <p>{@link SeedResult}를 재사용하지 않는다 — {@code matched}(새로 적재)·{@code alreadyExists}
 * (사전 필터로 건너뜀)가 resync에서는 의미가 맞지 않는다. resync는 이미 존재하는 행을
 * {@code existsByTmdbId} 필터 없이 전부 갱신하는 것이 목적이다.
 *
 * @param updated            {@code updateMetadata}로 갱신 성공 (시드의 {@code matched}와 달리 "새로"가 아니다)
 * @param skipped            편별 실패 (상세 조회 실패 등)
 * @param stoppedByRateLimit 429로 중도 중단됐는지
 * @param lastProcessedId    이어받기 커서. 다음 호출의 {@code fromId}로 넘긴다.
 *                           ⚠️ 429로 중단된 마지막 항목은 처리되지 않았으므로 여기에 반영하지 않는다 —
 *                           반영하면 재개 시 그 영화를 건너뛴다.
 */
public record ResyncResult(int updated, int skipped, boolean stoppedByRateLimit, Long lastProcessedId) {
}
