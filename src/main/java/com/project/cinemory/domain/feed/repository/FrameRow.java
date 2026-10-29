package com.project.cinemory.domain.feed.repository;

/**
 * 프레임 쿼리(4-2 ③) 한 행 — 활동 + 그 사람의 {@code newCount}({@code seenAfter}가 없으면 0).
 * {@code recordCount}는 {@code WATCH_RECORD} 프레임이 대표하는 시청 기록 수(그 외 타입은 {@code null}).
 */
public record FrameRow(ActivityRow activity, int newCount, Integer recordCount) {
}
