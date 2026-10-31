package com.project.cinemory.domain.export.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 내보내기 링크 발급 제한 — 같은 사용자 <b>1분에 1회</b>(settings-spec ST-5).
 *
 * <p>Caffeine 인메모리라 단일 인스턴스 전제다({@code RecommendationRateLimiter}와 같다). 한도를 넘긴 시도는 창을 연장하지 않는다.
 * 초과하면 {@link RateLimitedException}(429 + {@code Retry-After}).
 */
@Component
public class ExportRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Cache<Long, Instant> lastIssuedAt = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW.plusSeconds(5))
            .maximumSize(50_000)
            .build();

    public ExportRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 발급 한 번을 쓴다. 직전 발급이 1분 안이면 예외 — 이 경우 창은 그대로다. */
    public synchronized void acquire(long userId) {
        Instant now = clock.instant();
        Instant last = lastIssuedAt.getIfPresent(userId);
        if (last != null && now.isBefore(last.plus(WINDOW))) {
            long remainingMillis = Duration.between(now, last.plus(WINDOW)).toMillis();
            throw new RateLimitedException(ErrorCode.TOO_MANY_REQUESTS, (remainingMillis + 999) / 1000);
        }
        lastIssuedAt.put(userId, now);
    }
}
