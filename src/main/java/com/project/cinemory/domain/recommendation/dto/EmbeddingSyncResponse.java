package com.project.cinemory.domain.recommendation.dto;

import com.project.cinemory.domain.recommendation.service.EmbeddingSyncResult;

/** 임베딩 수동 실행 결과 (5-14-G). {@code lastProcessedId}를 다음 호출의 {@code afterId}로 넘겨 이어받는다. */
public record EmbeddingSyncResponse(int embedded, int unchanged, int failed,
                                    boolean stoppedByRateLimit, Long lastProcessedId) {

    public static EmbeddingSyncResponse from(EmbeddingSyncResult result) {
        return new EmbeddingSyncResponse(result.embedded(), result.unchanged(), result.failed(),
                result.stoppedByRateLimit(), result.lastProcessedId());
    }
}
