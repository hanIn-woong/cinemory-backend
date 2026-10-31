package com.project.cinemory.domain.export.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.project.cinemory.global.security.TokenHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * 내보내기 1회용 다운로드 토큰 (settings-spec ST-5).
 *
 * <p>32바이트 {@link SecureRandom} → Base64URL. 서버에는 <b>SHA-256 해시 → userId</b>만 Caffeine에 둔다(원문 미보관 — 메모리 덤프가
 * 새도 재사용할 값이 남지 않는다). TTL 10분, 소비 시 즉시 제거(1회용). 서버가 재시작하면 사라지고 사용자가 다시 누른다 —
 * 테이블을 만들지 않는다(단일 인스턴스 전제, 다중 인스턴스가 되면 Redis로 옮긴다. {@code OAuthNonceService}와 같다).
 */
@Service
public class ExportTokenService {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_ENTRIES = 10_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final Cache<String, Long> tokens;

    @Autowired
    public ExportTokenService(TokenHasher tokenHasher, Clock clock) {
        this(tokenHasher, clock, Ticker.systemTicker());
    }

    /** 만료 검증용 — 테스트가 Caffeine의 시계를 움직일 수 있게 한다. */
    ExportTokenService(TokenHasher tokenHasher, Clock clock, Ticker ticker) {
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.tokens = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    public record IssuedToken(String token, LocalDateTime expiresAt) {
    }

    public IssuedToken issue(Long userId) {
        byte[] entropy = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        tokens.put(tokenHasher.hash(token), userId);
        return new IssuedToken(token, LocalDateTime.now(clock).plus(TTL));
    }

    /**
     * 토큰을 소비하고 userId를 돌려준다. 없음·만료·이미 사용됨을 구분하지 않는다(모두 empty).
     * {@code asMap().remove()}는 원자적이라 같은 토큰으로 동시 요청이 와도 정확히 하나만 성공한다.
     */
    public Optional<Long> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokens.asMap().remove(tokenHasher.hash(token)));
    }
}
