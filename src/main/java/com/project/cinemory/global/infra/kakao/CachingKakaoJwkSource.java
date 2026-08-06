package com.project.cinemory.global.infra.kakao;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kakao.dto.JwkSetResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 카카오 JWKS를 조회해 {@code kid} → 공개키로 캐싱한다.
 *
 * <p><b>재조회 정책</b> — 캐시에 없는 {@code kid}가 들어왔을 때만 다시 받아온다.
 * 키 롤오버(카카오가 새 키로 서명하기 시작) 대응에 필수다.
 *
 * <p><b>⚠️ 그런데 그것만 두면 공격 수단이 된다.</b> 임의의 {@code kid}를 넣은 토큰을 반복 전송하면
 * 매번 JWKS 조회가 발생해 <b>카카오가 우리를 차단</b>할 수 있고, 그러면 소셜 로그인 전체가 죽는다.
 * 그래서 최소 재조회 간격(쿨다운)을 둔다. 쿨다운 중에는 조회하지 않고 바로 실패시킨다 —
 * 정상 사용자는 캐시 히트로 지나가므로 영향이 없다.
 *
 * <p>조회 실패는 <b>삼킨다.</b> 네트워크 문제로 갱신에 실패해도 이미 캐시된 키로 계속 동작해야 한다.
 * 실패를 전파하면 일시적 장애가 로그인 전체 중단으로 번진다.
 */
@Slf4j
@Component
public class CachingKakaoJwkSource implements KakaoJwkSource {

    private static final String RSA = "RSA";

    /** 폐기된 키를 영원히 들고 있지 않도록 한 번은 비운다. 롤오버 자체는 kid 미스로 처리된다. */
    private static final Duration KEY_TTL = Duration.ofHours(24);
    private static final int MAX_KEYS = 20;

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;
    private final Clock clock;

    private final Cache<String, RSAPublicKey> keyCache = Caffeine.newBuilder()
            .expireAfterWrite(KEY_TTL)
            .maximumSize(MAX_KEYS)
            .build();

    private final AtomicReference<Instant> lastRefreshAt = new AtomicReference<>(Instant.EPOCH);

    public CachingKakaoJwkSource(RestClient kakaoRestClient, KakaoOAuthProperties properties, Clock clock) {
        this.restClient = kakaoRestClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public RSAPublicKey findByKid(String kid) {
        if (kid == null || kid.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }

        RSAPublicKey cached = keyCache.getIfPresent(kid);
        if (cached != null) {
            return cached;
        }

        refreshIfCooldownPassed();

        RSAPublicKey refreshed = keyCache.getIfPresent(kid);
        if (refreshed == null) {
            log.warn("카카오 JWKS에서 kid={} 공개키를 찾지 못했습니다.", kid);
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        }
        return refreshed;
    }

    /**
     * 쿨다운이 지났을 때만 갱신한다.
     *
     * <p>CAS로 시각을 선점한 스레드만 실제 조회를 수행한다. 같은 순간 여러 요청이 캐시 미스를 내도
     * 카카오로 나가는 요청은 하나뿐이다.
     */
    private void refreshIfCooldownPassed() {
        Instant now = clock.instant();
        Instant last = lastRefreshAt.get();

        if (last.plus(properties.jwkRefreshCooldown()).isAfter(now)) {
            return;
        }
        if (!lastRefreshAt.compareAndSet(last, now)) {
            return;
        }
        fetchAndCache();
    }

    private void fetchAndCache() {
        try {
            JwkSetResponse response = restClient.get()
                    .uri(properties.jwksUri())
                    .retrieve()
                    .body(JwkSetResponse.class);

            if (response == null || response.keys() == null) {
                log.warn("카카오 JWKS 응답이 비어 있습니다.");
                return;
            }

            int loaded = 0;
            for (JwkSetResponse.Jwk jwk : response.keys()) {
                if (!RSA.equals(jwk.kty()) || jwk.kid() == null) {
                    continue;
                }
                keyCache.put(jwk.kid(), toPublicKey(jwk));
                loaded++;
            }
            log.info("카카오 JWKS 갱신 완료 — 공개키 {}건", loaded);

        } catch (RestClientException e) {
            // 캐시된 키로 계속 동작해야 하므로 전파하지 않는다
            log.warn("카카오 JWKS 조회 실패 — 기존 캐시로 계속 진행합니다. {}", e.getMessage());
        }
    }

    /**
     * JWKS의 {@code n}/{@code e}(Base64URL)를 RSA 공개키로 변환한다.
     *
     * <p><b>{@code new BigInteger(1, bytes)}의 첫 인자가 중요하다.</b> 부호를 명시하지 않으면
     * 최상위 비트가 1인 modulus가 <b>음수로 해석</b>되어 엉뚱한 키가 만들어지고,
     * 증상은 "서명 검증 실패"로만 나타나 원인 추적이 매우 어렵다.
     */
    private RSAPublicKey toPublicKey(JwkSetResponse.Jwk jwk) {
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            BigInteger modulus = new BigInteger(1, decoder.decode(jwk.n()));
            BigInteger exponent = new BigInteger(1, decoder.decode(jwk.e()));

            return (RSAPublicKey) KeyFactory.getInstance(RSA)
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));

        } catch (NoSuchAlgorithmException e) {
            // RSA는 모든 JVM이 반드시 제공한다. 여기 도달하면 런타임 자체가 비정상이다.
            throw new IllegalStateException("RSA KeyFactory를 사용할 수 없습니다.", e);
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("카카오 JWKS의 공개키 형식이 올바르지 않습니다. kid=" + jwk.kid(), e);
        }
    }
}
