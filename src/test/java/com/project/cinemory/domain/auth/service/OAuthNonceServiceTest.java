package com.project.cinemory.domain.auth.service;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이 테스트가 고정하는 불변식은 {@code docs/security-spec.md} S-9 E-1에 표로 정리돼 있다.
 * 대부분 깨져도 컴파일은 통과하므로 <b>정리 대상으로 오해해 삭제하지 말 것.</b>
 *
 * <p>특히 <b>1회용 소비</b>와 <b>동시 소비 시 정확히 하나만 성공</b>은 재전송 방지의 근거 자체다.
 * 이 둘이 깨지면 nonce를 도입한 의미가 사라진다.
 */
class OAuthNonceServiceTest {

    private OAuthNonceService service(Duration ttl) {
        return new OAuthNonceService(ttl);
    }

    private OAuthNonceService service() {
        return service(Duration.ofMinutes(5));
    }

    @Test
    void 발급한_nonce는_소비할_수_있다() {
        OAuthNonceService service = service();

        String nonce = service.issue();

        assertThatCode(() -> service.consumeOrThrow(nonce)).doesNotThrowAnyException();
    }

    /**
     * <b>재전송 방지의 핵심.</b> 소비된 nonce가 다시 통과하면 탈취된 ID 토큰을
     * 같은 nonce로 재전송할 수 있게 되어 도입 목적이 사라진다.
     */
    @Test
    void 소비된_nonce는_다시_쓸_수_없다() {
        OAuthNonceService service = service();
        String nonce = service.issue();
        service.consumeOrThrow(nonce);

        assertThatThrownBy(() -> service.consumeOrThrow(nonce))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    @Test
    void 발급하지_않은_값은_INVALID_NONCE다() {
        OAuthNonceService service = service();

        assertThatThrownBy(() -> service.consumeOrThrow("우리가-발급하지-않은-값"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    /** 클라이언트가 nonce 필드를 아예 빠뜨린 경우 NPE가 아니라 도메인 예외로 떨어져야 한다. */
    @Test
    void null과_공백도_INVALID_NONCE다() {
        OAuthNonceService service = service();

        assertThatThrownBy(() -> service.consumeOrThrow(null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);

        assertThatThrownBy(() -> service.consumeOrThrow("   "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    @Test
    void nonce는_매번_다른_256bit_랜덤값이다() {
        OAuthNonceService service = service();

        Set<String> issued = IntStream.range(0, 1_000)
                .mapToObj(i -> service.issue())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(issued).hasSize(1_000); // 충돌 없음
        assertThat(issued).allSatisfy(nonce -> assertThat(nonce).hasSize(43)); // 32바이트 Base64URL(패딩 없음)
    }

    /** Base64URL이 아니면 URL/JSON 경로에서 이스케이프 문제가 생긴다. 패딩({@code =})도 없어야 한다. */
    @Test
    void nonce는_패딩_없는_Base64URL이다() {
        OAuthNonceService service = service();

        assertThat(service.issue()).matches("^[A-Za-z0-9_-]{43}$");
    }

    /**
     * TTL이 지나면 Caffeine이 스스로 만료시킨다 — 별도 정리 배치가 없다는 설계의 근거다.
     * 시간을 고정할 수 없어(캐시가 내부 ticker를 쓴다) 짧은 TTL과 실제 대기로 확인한다.
     */
    @Test
    void TTL이_지난_nonce는_INVALID_NONCE다() throws InterruptedException {
        OAuthNonceService service = service(Duration.ofMillis(100));
        String nonce = service.issue();

        Thread.sleep(300);

        assertThatThrownBy(() -> service.consumeOrThrow(nonce))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    /**
     * {@code asMap().remove()}가 원자적이라는 주장을 실제로 고정한다.
     * 조회 후 삭제로 나누면 그 사이에 둘 이상이 통과할 수 있고, 그 순간 재전송 방지가 뚫린다.
     */
    @Test
    void 동시에_같은_nonce를_소비해도_정확히_하나만_성공한다() throws InterruptedException {
        OAuthNonceService service = service();
        String nonce = service.issue();

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        service.consumeOrThrow(nonce);
                        succeeded.incrementAndGet();
                    } catch (BusinessException expected) {
                        // 나머지 31개는 여기로 떨어져야 정상
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).hasValue(1);
    }

    /** 서로 다른 nonce는 서로의 소비에 영향을 주지 않는다. */
    @Test
    void 다른_nonce의_소비는_서로_독립적이다() {
        OAuthNonceService service = service();
        String first = service.issue();
        String second = service.issue();

        service.consumeOrThrow(first);

        assertThatCode(() -> service.consumeOrThrow(second)).doesNotThrowAnyException();
    }

    /** TTL 오타/누락을 런타임이 아니라 기동 시점에 잡는다 — {@code JwtProperties}와 같은 규칙이다. */
    @Test
    void 잘못된_TTL은_기동_시점에_거부된다() {
        assertThatThrownBy(() -> service(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ttlSeconds는_설정값을_초로_돌려준다() {
        assertThat(service(Duration.ofMinutes(5)).ttlSeconds()).isEqualTo(300);
    }

    /** 동시 발급에서도 값이 겹치지 않아야 한다({@code SecureRandom}은 thread-safe). */
    @Test
    void 동시에_발급해도_nonce가_겹치지_않는다() throws InterruptedException {
        OAuthNonceService service = service();
        int threads = 16;
        int perThread = 100;

        Set<String> issued = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        for (int j = 0; j < perThread; j++) {
                            issued.add(service.issue());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(issued).hasSize(threads * perThread);
    }
}
