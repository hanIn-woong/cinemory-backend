package com.project.cinemory.domain.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 소셜 로그인 재전송 방지를 위한 nonce 발급·소비 (S-9 E-1).
 *
 * <p><b>왜 필요한가</b> — 카카오 ID 토큰의 수명은 약 2시간이다. 서명·{@code iss}·{@code aud}·{@code exp}가
 * 모두 유효한 토큰을 탈취하면 그 2시간 내내 로그인에 쓸 수 있다. nonce는 "이 토큰이 <b>우리가 방금 시작한
 * 로그인 시도</b>에 대한 응답인가"를 확인해 그 창을 닫는다.
 *
 * <p><b>인메모리인 이유</b> — 단일 인스턴스 전제이고 nonce는 5분짜리 일회성 값이라 영속화할 이유가 없다.
 * Caffeine의 {@code expireAfterWrite}가 스스로 만료시키므로 <b>정리 배치가 필요 없다</b> —
 * 만료 리프레시 토큰 정리를 보류한 판단과 같은 맥락이다. 다중 인스턴스로 확장하면 Redis로 옮긴다.
 *
 * <p><b>평문 보관</b> — nonce는 비밀이 아니라 일회성 대조값이고 프로세스 메모리에만 짧게 존재한다.
 * 리프레시 토큰처럼 해시로 보관할 이유가 없다.
 */
@Service
public class OAuthNonceService {

    /** 256bit. 리프레시 토큰과 같은 엔트로피 기준을 쓴다. */
    private static final int NONCE_BYTES = 32;

    private static final int MAX_ENTRIES = 10_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Cache<String, Boolean> issuedNonces;
    private final Duration ttl;

    public OAuthNonceService(@Value("${auth.oauth.nonce-ttl}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("auth.oauth.nonce-ttl은 0보다 큰 Duration이어야 합니다.");
        }
        this.ttl = ttl;
        // maximumSize는 발급 폭주 시의 메모리 방어다. TTL이 짧아 실제로 닿을 일은 거의 없다.
        this.issuedNonces = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    /** 값 자체에 의미는 없다. <b>캐시에 존재한다는 사실</b>이 "우리가 발급했고 아직 안 쓰였다"를 뜻한다. */
    public String issue() {
        byte[] entropy = new byte[NONCE_BYTES];
        secureRandom.nextBytes(entropy);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        issuedNonces.put(nonce, Boolean.TRUE);
        return nonce;
    }

    /**
     * 대조 후 <b>즉시 제거</b>한다. 1회용이어야 재전송 방지가 성립하기 때문이다.
     *
     * <p>{@code asMap().remove()}는 원자적이라 같은 nonce로 동시 요청이 들어와도
     * <b>정확히 하나만 성공</b>한다. 조회 후 삭제로 나누면 그 사이에 둘 다 통과할 수 있다.
     *
     * @throws BusinessException {@code INVALID_NONCE} — 만료됐거나, 우리가 발급하지 않았거나, 이미 소비됨
     */
    public void consumeOrThrow(String nonce) {
        if (nonce == null || nonce.isBlank() || issuedNonces.asMap().remove(nonce) == null) {
            throw new BusinessException(ErrorCode.INVALID_NONCE);
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
