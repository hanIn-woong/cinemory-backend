package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 분당 10 · 일 200(KST 자정 기준) — 시계를 직접 움직여 창 경계를 검증한다. */
class RecommendationRateLimiterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 테스트가 시간을 움직일 수 있는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static MutableClock clockAt(String kstLocalDateTime) {
        return new MutableClock(java.time.LocalDateTime.parse(kstLocalDateTime).atZone(KST).toInstant());
    }

    @Test
    void 분당_10번까지는_통과하고_11번째는_429다() {
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clockAt("2026-10-26T12:00:00"));

        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }

        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_RATE_LIMITED);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(60);
                });
    }

    @Test
    void Retry_After는_창이_끝날_때까지_남은_초다() {
        MutableClock clock = clockAt("2026-10-26T12:00:00");
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clock);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }
        clock.advance(Duration.ofSeconds(25));

        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(35));
    }

    @Test
    void 분이_지나면_창이_초기화된다() {
        MutableClock clock = clockAt("2026-10-26T12:00:00");
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clock);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }
        clock.advance(Duration.ofSeconds(60));

        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }

    @Test
    void 사용자마다_따로_센다() {
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clockAt("2026-10-26T12:00:00"));
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }

        assertThatCode(() -> limiter.acquire(2L)).doesNotThrowAnyException();
    }

    @Test
    void 한도를_넘긴_시도는_카운트에_더하지_않는다() {
        MutableClock clock = clockAt("2026-10-26T12:00:00");
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clock);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(RateLimitedException.class);
        }
        clock.advance(Duration.ofSeconds(60));
        // 거절된 5번이 일 카운트에 쌓였다면 아래 190번째쯤에서 막힌다 — 정확히 200번까지 통과해야 한다.
        int passed = 0;
        for (int minute = 0; minute < 40; minute++) {
            for (int i = 0; i < 10; i++) {
                try {
                    limiter.acquire(1L);
                    passed++;
                } catch (RateLimitedException e) {
                    break;
                }
            }
            clock.advance(Duration.ofSeconds(60));
        }

        assertThat(passed + 10).isEqualTo(200);
    }

    @Test
    void 일_200번이_넘으면_KST_자정까지의_초를_Retry_After로_준다() {
        MutableClock clock = clockAt("2026-10-26T23:00:00");
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clock);
        for (int minute = 0; minute < 20; minute++) {
            for (int i = 0; i < 10; i++) {
                limiter.acquire(1L);
            }
            clock.advance(Duration.ofSeconds(60));
        }
        // 23:00 + 20분 = 23:20 — 오늘 200번 소진. 자정까지 40분.
        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(40 * 60));
    }

    @Test
    void KST_자정이_지나면_일_카운트가_초기화된다() {
        MutableClock clock = clockAt("2026-10-26T23:00:00");
        RecommendationRateLimiter limiter = new RecommendationRateLimiter(RecommendFixtures.properties(), clock);
        for (int minute = 0; minute < 20; minute++) {
            for (int i = 0; i < 10; i++) {
                limiter.acquire(1L);
            }
            clock.advance(Duration.ofSeconds(60));
        }
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(RateLimitedException.class);

        clock.advance(Duration.ofMinutes(41));   // 00:01 KST

        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }
}
