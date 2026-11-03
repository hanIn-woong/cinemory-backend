package com.project.cinemory.domain.recommendation.repository;

/**
 * 취향 프로필이 바뀌었는지 알아보는 지문 (M3b-recommendation-spec R-5-3) - 시청 기록 수 · 찜 수 · 최대 {@code watch_record.id} ·
 * 최대 {@code wish_movie.id}. 값이 하나라도 바뀌면 현재 묶음을 버리고 새로 뽑는다(방금 본 영화가 남지 않게).
 * 프로필 전체를 다시 집계하지 않고 쿼리 2개로 얻는다. 기록이 없으면 최대 id는 0이다.
 */
public record ProfileFingerprint(long watchCount, long wishCount, long maxWatchRecordId, long maxWishMovieId) {
}
