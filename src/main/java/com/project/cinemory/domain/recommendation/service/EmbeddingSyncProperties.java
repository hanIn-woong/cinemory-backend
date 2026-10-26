package com.project.cinemory.domain.recommendation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code cinemory.recommend.embedding-sync.*} — 값의 의미는 application.yml 주석.
 *
 * @param scheduledLimit 스케줄 1회 실행에서 임베딩 요청을 보낼 영화 수 상한(OP-1-2, 기본 800). 관리자 수동 실행은 이 값을 쓰지 않고
 *                       요청 파라미터 {@code limit}(훑는 영화 수)을 따른다. 0 이하면 상한 없음.
 */
@ConfigurationProperties(prefix = "cinemory.recommend.embedding-sync")
public record EmbeddingSyncProperties(
        String cron,
        int batchSize,
        int maxRetries,
        Duration backoff,
        Duration maxWait,
        Duration minInterval,
        int warnEmbeddedThreshold,
        @DefaultValue("800") int scheduledLimit) {
}
