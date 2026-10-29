package com.project.cinemory.domain.feed.repository;

import com.project.cinemory.domain.feed.entity.ActivityType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 활동 합성 쿼리(4-1)의 한 행. {@code sourceId}는 원천 PK이고 {@code COLLECTION_MOVIES_ADDED}만 {@code collection_id}다
 * (이때 {@code groupDate}가 함께 키가 된다).
 */
public record ActivityRow(Long userId, ActivityType type, Long sourceId, LocalDateTime occurredAt, LocalDate groupDate) {
}
