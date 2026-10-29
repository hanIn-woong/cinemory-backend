package com.project.cinemory.domain.feed.dto;

import com.project.cinemory.domain.feed.entity.ActivityType;
import com.project.cinemory.domain.movie.dto.MovieSummaryResponse;

import java.time.LocalDateTime;

/**
 * 스트립 프레임·티켓 피드 공용 (3-3). 타입별로 해당 필드만 채우고 나머지는 {@code null}이다.
 * ⚠️ {@code private_review} 필드는 두지 않는다(SF-10) — 내 피드에서도 마찬가지다.
 */
public record ActivityResponse(
        ActivityType type,
        LocalDateTime occurredAt,
        Long sourceId,
        MovieSummaryResponse movie,
        WatchDetail watch,
        String reviewContent,
        CollectionDetail collection,
        AddedMovies added,
        FeedUserResponse followee
) {

    /** 스트립 프레임용 — 합쳐진 시청 기록 수를 {@code watch}에 싣는다. */
    public ActivityResponse withWatchRecordCount(Integer count) {
        return watch == null ? this : new ActivityResponse(type, occurredAt, sourceId, movie, watch.withRecordCount(count),
                reviewContent, collection, added, followee);
    }
}
