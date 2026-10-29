package com.project.cinemory.domain.feed.entity;

import java.util.Arrays;

/**
 * 피드 활동 6종 (social-feed-spec 2절). DB에 저장되지 않는 조회 전용 구분이다.
 *
 * <p>{@code sortKey}는 커서 동점 처리용 정렬 키이고 SQL에 리터럴로 들어간다. ⚠️ {@code ordinal()}에 의존하지 않는다 —
 * 값을 바꾸면 클라이언트가 들고 있는 커서가 깨지므로 <b>고정</b>이다.
 */
public enum ActivityType {

    WATCH_RECORD(1),
    REVIEW(2),
    WISH(3),
    COLLECTION_CREATED(4),
    COLLECTION_MOVIES_ADDED(5),
    FOLLOW(6);

    private final int sortKey;

    ActivityType(int sortKey) {
        this.sortKey = sortKey;
    }

    public int sortKey() {
        return sortKey;
    }

    /** 알 수 없는 키면 {@code null} — 호출자가 커서 오류로 처리한다. */
    public static ActivityType fromSortKey(int sortKey) {
        return Arrays.stream(values()).filter(type -> type.sortKey == sortKey).findFirst().orElse(null);
    }
}
