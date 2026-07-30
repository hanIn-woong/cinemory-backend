package com.project.cinemory.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 관련 설정. TTL을 {@link Duration}(ISO-8601)으로 받으므로 오타가 있으면
 * 런타임이 아니라 <b>기동 시점에</b> 바인딩이 실패한다.
 *
 * <p>{@code secret}은 {@code application-secret.yml}(.gitignore 대상)에 두고,
 * 배포 시에는 환경변수로 주입한다.
 *
 * @param secret            HS256 서명 키. UTF-8 기준 32바이트(256bit) 이상이어야 한다
 * @param accessTokenTtl    Access Token 유효 기간
 * @param refreshTokenTtl   Refresh Token 유효 기간
 * @param refreshReuseGrace 회전 직후 동시 재발급을 재사용(탈취)으로 오판하지 않기 위한 유예 시간
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshReuseGrace
) {

    /** HS256이 요구하는 최소 키 길이(256bit). */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret은 UTF-8 기준 " + MIN_SECRET_BYTES + "바이트(256bit) 이상이어야 합니다.");
        }
        requirePositive(accessTokenTtl, "jwt.access-token-ttl");
        requirePositive(refreshTokenTtl, "jwt.refresh-token-ttl");
        requirePositive(refreshReuseGrace, "jwt.refresh-reuse-grace");
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + "은 0보다 큰 Duration이어야 합니다.");
        }
    }
}
