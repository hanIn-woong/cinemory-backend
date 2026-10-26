package com.project.cinemory.domain.recommendation.dto;

/** 자연어 질의에서 뽑은 조건 종류 (controller-layer-spec 5-14-C). */
public enum ConditionType {
    RUNTIME_MAX, RUNTIME_PREFER_SHORT, RUNTIME_PREFER_LONG,   // Q-1
    COUNTRY, EXCLUDE_COUNTRY, RELEASE_PERIOD,                 // Q-2-1 · Q-2-2
    EXCLUDE_GENRE, AVOID_MOOD                                 // Q-2-3 ① · ②
}
