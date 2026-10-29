package com.project.cinemory.domain.feed.repository;

import java.time.LocalDateTime;

/** 팔로잉 스트립 후보(4-2 ②) — 사람과 그 사람의 마지막 활동 시각. */
public record StripRow(Long userId, LocalDateTime lastActivityAt) {
}
