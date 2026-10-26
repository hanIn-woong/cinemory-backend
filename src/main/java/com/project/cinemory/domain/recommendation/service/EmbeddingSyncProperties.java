package com.project.cinemory.domain.recommendation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code cinemory.recommend.embedding-sync.*} — 값의 의미는 application.yml 주석. */
@ConfigurationProperties(prefix = "cinemory.recommend.embedding-sync")
public record EmbeddingSyncProperties(
        String cron,
        int batchSize,
        int maxRetries,
        Duration backoff,
        Duration maxWait,
        Duration minInterval,
        int warnEmbeddedThreshold) {
}
