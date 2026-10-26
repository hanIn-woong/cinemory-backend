package com.project.cinemory.domain.recommendation.dto;

/** 추천 이유 (5-14-C). {@code text}는 서버가 만든 한국어 문구, {@code targetId}·{@code label}은 인물/장르/국가일 때만. */
public record RecommendReasonResponse(ReasonType type, Long targetId, String label, String text) {
}
