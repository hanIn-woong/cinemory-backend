package com.project.cinemory.domain.feed.repository;

/** 프레임 쿼리(4-2 ③) 한 행 — 활동 + 그 사람의 {@code newCount}({@code seenAfter}가 없으면 0). */
public record FrameRow(ActivityRow activity, int newCount) {
}
