package com.project.cinemory.domain.export.service;

import com.github.benmanes.caffeine.cache.Ticker;
import com.project.cinemory.domain.export.service.ExportTokenService.IssuedToken;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.RateLimitedException;
import com.project.cinemory.global.security.TokenHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 토큰 만료·1회용·발급 제한 — 시계를 직접 움직여 경계를 검증한다 (settings-spec ST-T3, ST-T4). */
class ExportTokenServiceTest {

    /** Caffeine의 시계와 Clock을 한 곳에서 움직인다. */
    private static final class TestTime extends Clock implements Ticker {
        private final AtomicLong nanos = new AtomicLong();
        private final Instant start = Instant.parse("2026-10-31T00:00:00Z");

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
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
            return start.plusNanos(nanos.get());
        }
    }

    private final TestTime time = new TestTime();
    private final ExportTokenService tokens = new ExportTokenService(new TokenHasher(), time, time);

    @Test
    void T3_10분이_지나면_만료되고_직전까지는_유효하다() {
        IssuedToken early = tokens.issue(7L);
        IssuedToken late = tokens.issue(8L);

        time.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(tokens.consume(early.token())).contains(7L);

        time.advance(Duration.ofSeconds(1));
        assertThat(tokens.consume(late.token())).isEmpty();
    }

    @Test
    void 소비하면_즉시_제거되어_재사용할_수_없다() {
        IssuedToken issued = tokens.issue(7L);

        assertThat(tokens.consume(issued.token())).contains(7L);
        assertThat(tokens.consume(issued.token())).isEmpty();
    }

    @Test
    void 발급한_적_없는_값_빈_값_null은_모두_empty다() {
        tokens.issue(7L);

        assertThat(tokens.consume("nope")).isEmpty();
        assertThat(tokens.consume("")).isEmpty();
        assertThat(tokens.consume("  ")).isEmpty();
        assertThat(tokens.consume(null)).isEmpty();
    }

    @Test
    void 토큰은_32바이트_Base64URL이고_매번_다르며_만료_시각은_10분_뒤다() {
        IssuedToken a = tokens.issue(1L);
        IssuedToken b = tokens.issue(1L);

        assertThat(a.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(a.token()).isNotEqualTo(b.token());
        assertThat(a.expiresAt()).isEqualTo(java.time.LocalDateTime.now(time).plusMinutes(10));
    }

    @Test
    void T4_발급_제한은_1분_창이고_사용자별이며_초과가_창을_늘리지_않는다() {
        ExportRateLimiter limiter = new ExportRateLimiter(time);

        limiter.acquire(1L);
        limiter.acquire(2L); // 다른 사용자는 영향 없음

        time.advance(Duration.ofSeconds(25));
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOfSatisfying(RateLimitedException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(35);
        });

        // 거부된 시도가 창을 밀지 않는다 — 첫 발급으로부터 60초면 통과
        time.advance(Duration.ofSeconds(35));
        limiter.acquire(1L);
    }
}
