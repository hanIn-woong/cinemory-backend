package com.project.cinemory.domain.recommendation.dto;

/** 질의에서 적용한 조건 (5-14-C). {@code value}는 분 단위 기준값. */
public record AppliedConditionResponse(ConditionType type, Integer value, String text) {
}
