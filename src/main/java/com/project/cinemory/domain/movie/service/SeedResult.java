package com.project.cinemory.domain.movie.service;

/**
 * {@link MovieSeedService} 두 진입점(박스오피스 역방향 / discover)의 공통 결과 (tmdb-sync-spec 6-5).
 *
 * @param matched           새로 적재 성공
 * @param skipped           실패해서 건너뜀 (역방향=제목 매칭 실패, discover=상세 조회 실패)
 * @param alreadyExists     {@code existsByTmdbId} 사전 필터로 건너뜀 — 실패가 아니다.
 *                          {@code skipped}에 섞으면 "매칭 실패가 많다"로 오독된다
 * @param stoppedByRateLimit 429로 중도 중단됐는지. 이게 없으면 "다 끝남"과 "중간에 멈춤"이
 *                          구별되지 않는다 — 후자면 멱등이므로 다시 실행하면 이어받는다
 */
public record SeedResult(int matched, int skipped, int alreadyExists, boolean stoppedByRateLimit) {
}
