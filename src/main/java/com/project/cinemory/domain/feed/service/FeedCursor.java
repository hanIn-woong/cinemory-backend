package com.project.cinemory.domain.feed.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.cinemory.domain.feed.entity.ActivityType;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 피드 커서 (3-4) — JSON → Base64URL. 스트립은 {@code (asOf, at, uid)}, 개인 피드는 {@code (asOf, at, k, id, d)}를 쓴다.
 * 디코드 실패·필드 누락은 400 {@code INVALID_INPUT_VALUE}.
 *
 * <p>{@code d}(groupDate)는 정렬에는 쓰이지 않는다 — {@code COLLECTION_MOVIES_ADDED}는 날짜가 다르면 {@code occurredAt}도 달라
 * {@code (occurredAt, sortKey, sourceId)}만으로 유일하다. 위치를 사람이 읽을 수 있게 남겨 둘 뿐이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedCursor(LocalDateTime asOf, LocalDateTime at, Long uid, Integer k, Long id, LocalDate d) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static FeedCursor ofStrip(LocalDateTime asOf, LocalDateTime lastActivityAt, Long userId) {
        return new FeedCursor(asOf, lastActivityAt, userId, null, null, null);
    }

    public static FeedCursor ofActivity(LocalDateTime asOf, LocalDateTime occurredAt, ActivityType type, Long sourceId,
                                        LocalDate groupDate) {
        return new FeedCursor(asOf, occurredAt, null, type.sortKey(), sourceId, groupDate);
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(this));
    }

    /** 스트립 커서 — {@code asOf · at · uid} 필수. */
    public static FeedCursor decodeStrip(String raw) {
        FeedCursor cursor = decode(raw);
        if (cursor.asOf == null || cursor.at == null || cursor.uid == null) {
            throw invalid();
        }
        return cursor;
    }

    /** 개인 피드 커서 — {@code asOf · at · k · id} 필수, {@code k}는 알려진 정렬 키여야 한다. */
    public static FeedCursor decodeActivity(String raw) {
        FeedCursor cursor = decode(raw);
        if (cursor.asOf == null || cursor.at == null || cursor.k == null || cursor.id == null
                || ActivityType.fromSortKey(cursor.k) == null) {
            throw invalid();
        }
        return cursor;
    }

    private static FeedCursor decode(String raw) {
        FeedCursor cursor;
        try {
            byte[] json = Base64.getUrlDecoder().decode(raw.getBytes(StandardCharsets.US_ASCII));
            cursor = MAPPER.readValue(json, FeedCursor.class);
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (cursor == null) {
            throw invalid();
        }
        return cursor;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
    }
}
