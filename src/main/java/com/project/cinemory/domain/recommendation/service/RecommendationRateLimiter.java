package com.project.cinemory.domain.recommendation.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 자연어 추천 호출 제한 (controller-layer-spec 5-14-E, M3b-recommendation-spec A-1-4).
 *
 * <p><b>실제 Gemini 호출 직전에만</b> {@link #acquire}한다 — 결과 캐시 적중·질의 임베딩 캐시 적중·{@code page ≥ 1}은 세지 않는다.
 * 사용자당 분당 N · 일 M(프로퍼티). 일 단위는 KST 자정에 초기화된다. Caffeine 인메모리라 <b>단일 인스턴스 전제</b>다(security-spec L-1과 같다).
 * 마지막 방어선은 Google 쪽 할당량 상한(deploy-spec 11-A).
 *
 * <p>분당은 첫 호출부터 60초의 고정 창이다. 초과하면 {@link RateLimitedException}(429 + {@code Retry-After}).
 * 한도를 넘긴 시도는 카운트에 더하지 않는다.
 */
@Component
public class RecommendationRateLimiter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final RecommendProperties.RateLimit limit;
    private final Clock clock;

    private final Cache<Long, MinuteWindow> minuteWindows = Caffeine.newBuilder()
            .expireAfterWrite(MINUTE.plusSeconds(5))
            .maximumSize(50_000)
            .build();
    private final Cache<Long, DayCount> dayCounts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(26))
            .maximumSize(50_000)
            .build();

    public RecommendationRateLimiter(RecommendProperties properties, Clock clock) {
        this.limit = properties.rateLimit();
        this.clock = clock;
    }

    private record MinuteWindow(Instant start, int count) {
    }

    private record DayCount(LocalDate date, int count) {
    }

    /** 탈퇴한 사용자의 카운트를 지운다 — 같은 id가 재사용돼도 남의 한도를 물려받지 않게 한다. */
    public synchronized void evict(long userId) {
        minuteWindows.invalidate(userId);
        dayCounts.invalidate(userId);
    }

    /** 호출 한 번을 쓴다. 한도 초과면 예외 — 이 경우 카운트는 오르지 않는다. */
    public synchronized void acquire(long userId) {
        Instant now = clock.instant();
        ZonedDateTime nowKst = now.atZone(KST);
        LocalDate today = nowKst.toLocalDate();

        DayCount day = dayCounts.getIfPresent(userId);
        if (day == null || !day.date().equals(today)) {
            day = new DayCount(today, 0);
        }
        MinuteWindow window = minuteWindows.getIfPresent(userId);
        if (window == null || !now.isBefore(window.start().plus(MINUTE))) {
            window = new MinuteWindow(now, 0);
        }

        if (day.count() >= limit.perDay()) {
            long secondsToMidnight = Duration.between(nowKst, today.plusDays(1).atStartOfDay(KST)).toSeconds();
            throw new RateLimitedException(ErrorCode.RECOMMENDATION_RATE_LIMITED, secondsToMidnight);
        }
        if (window.count() >= limit.perMinute()) {
            long remainingMillis = Duration.between(now, window.start().plus(MINUTE)).toMillis();
            throw new RateLimitedException(ErrorCode.RECOMMENDATION_RATE_LIMITED, (remainingMillis + 999) / 1000);
        }

        minuteWindows.put(userId, new MinuteWindow(window.start(), window.count() + 1));
        dayCounts.put(userId, new DayCount(today, day.count() + 1));
    }
}
