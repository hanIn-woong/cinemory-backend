package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이 테스트가 고정하는 불변식은 {@code docs/security-spec.md} S-2에 표로 정리돼 있다.
 * 대부분 깨져도 컴파일은 통과하므로 <b>정리 대상으로 오해해 삭제하지 말 것.</b>
 */
class JwtTokenProviderTest {

    private static final String SECRET = "cinemory-test-secret-key-0123456789-abcdefghijklmnop";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-30T00:00:00Z");

    private JwtTokenProvider provider(Duration accessTtl, Instant now) {
        return provider(SECRET, accessTtl, now);
    }

    private JwtTokenProvider provider(String secret, Duration accessTtl, Instant now) {
        return new JwtTokenProvider(
                new JwtProperties(secret, accessTtl, Duration.ofDays(14), Duration.ofSeconds(30)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** JWT 헤더(첫 세그먼트)를 디코드한다. Base64URL, 패딩 없음. */
    private String headerOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
    }

    @Test
    void 발급한_Access_Token을_파싱하면_주체가_복원된다() {
        JwtTokenProvider provider = provider(Duration.ofMinutes(30), FIXED_NOW);

        String token = provider.createAccessToken(42L, RoleType.ADMIN);
        AuthUserPrincipal principal = provider.parseAccessToken(token);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo(RoleType.ADMIN);
    }

    /**
     * 이 파일이 만들어진 계기였던 문제를 고정한다.
     *
     * <p>라운드트립 테스트는 발급·검증 양쪽이 똑같이 HS512여도 통과하므로 알고리즘을 검증하지 못한다.
     * 64바이트 secret은 {@code Keys.hmacShaKeyFor()}였다면 HS512가 선택됐을 길이이며,
     * 운영 설정값도 이 길이대다.
     *
     * <p>이 테스트가 통과하면 알고리즘은 고정된 것이다. 다만 현재 가드가 두 겹이라
     * ({@code SecretKeySpec}의 JCA 이름 / {@code signWith}의 명시적 {@code Jwts.SIG.HS256} 인자)
     * <b>어느 쪽이 실효인지는 아직 구분되지 않는다</b> — 후자만으로 충분하다면 전자는 걷어낼 수 있다.
     */
    @Test
    void 긴_secret에서도_서명_알고리즘은_HS256으로_고정된다() {
        String longSecret = "x".repeat(64);
        JwtTokenProvider provider = provider(longSecret, Duration.ofMinutes(30), FIXED_NOW);

        String token = provider.createAccessToken(1L, RoleType.USER);

        assertThat(headerOf(token)).contains("\"alg\":\"HS256\"");
    }

    @Test
    void 만료된_토큰은_TOKEN_EXPIRED다() {
        Duration ttl = Duration.ofMinutes(30);
        String token = provider(ttl, FIXED_NOW).createAccessToken(1L, RoleType.USER);

        // TTL이 지난 시점의 검증자 — 시간을 고정했으므로 sleep이 필요 없다
        JwtTokenProvider afterExpiry = provider(ttl, FIXED_NOW.plus(ttl).plusSeconds(1));

        assertThatThrownBy(() -> afterExpiry.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 만료_직전_토큰은_아직_유효하다() {
        Duration ttl = Duration.ofMinutes(30);
        String token = provider(ttl, FIXED_NOW).createAccessToken(7L, RoleType.USER);

        JwtTokenProvider justBeforeExpiry = provider(ttl, FIXED_NOW.plus(ttl).minusSeconds(1));

        assertThat(justBeforeExpiry.parseAccessToken(token).userId()).isEqualTo(7L);
    }

    @Test
    void 서명이_다른_토큰은_INVALID_TOKEN이다() {
        String token = provider(Duration.ofMinutes(30), FIXED_NOW).createAccessToken(1L, RoleType.USER);
        JwtTokenProvider other = provider(SECRET + "-different", Duration.ofMinutes(30), FIXED_NOW);

        assertThatThrownBy(() -> other.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void 형식이_아닌_문자열도_INVALID_TOKEN이다() {
        JwtTokenProvider provider = provider(Duration.ofMinutes(30), FIXED_NOW);

        assertThatThrownBy(() -> provider.parseAccessToken("not-a-jwt"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void Refresh_Token은_매번_다른_256bit_랜덤값이다() {
        JwtTokenProvider provider = provider(Duration.ofMinutes(30), FIXED_NOW);

        String first = provider.createRefreshToken();
        String second = provider.createRefreshToken();

        assertThat(first).hasSize(43).isNotEqualTo(second); // 32바이트 Base64URL(패딩 없음)
    }

    @Test
    void Refresh_Token은_JWT가_아니다() {
        String refreshToken = provider(Duration.ofMinutes(30), FIXED_NOW).createRefreshToken();

        assertThat(refreshToken).doesNotContain(".");
    }

    @Test
    void 짧은_secret은_기동_시점에_거부된다() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(30),
                Duration.ofDays(14), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
