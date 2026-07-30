package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "cinemory-test-secret-key-0123456789-abcdefghijklmnop";

    private JwtTokenProvider provider(Duration accessTtl) {
        return new JwtTokenProvider(new JwtProperties(SECRET, accessTtl, Duration.ofDays(14), Duration.ofSeconds(30)));
    }

    @Test
    void 발급한_Access_Token을_파싱하면_주체가_복원된다() {
        JwtTokenProvider provider = provider(Duration.ofMinutes(30));

        String token = provider.createAccessToken(42L, RoleType.ADMIN);
        AuthUserPrincipal principal = provider.parseAccessToken(token);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo(RoleType.ADMIN);
    }

    @Test
    void 만료된_토큰은_TOKEN_EXPIRED다() throws Exception {
        JwtTokenProvider provider = provider(Duration.ofMillis(1));
        String token = provider.createAccessToken(1L, RoleType.USER);
        Thread.sleep(50);

        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 서명이_다른_토큰은_INVALID_TOKEN이다() {
        String token = provider(Duration.ofMinutes(30)).createAccessToken(1L, RoleType.USER);
        JwtTokenProvider other = new JwtTokenProvider(
                new JwtProperties(SECRET + "-different", Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofSeconds(30)));

        assertThatThrownBy(() -> other.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void 형식이_아닌_문자열도_INVALID_TOKEN이다() {
        JwtTokenProvider provider = provider(Duration.ofMinutes(30));

        assertThatThrownBy(() -> provider.parseAccessToken("not-a-jwt"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void Refresh_Token은_매번_다른_256bit_랜덤값이다() {
        JwtTokenProvider provider = provider(Duration.ofMinutes(30));

        String first = provider.createRefreshToken();
        String second = provider.createRefreshToken();

        assertThat(first).hasSize(43).isNotEqualTo(second); // 32바이트 Base64URL(패딩 없음)
    }

    @Test
    void 짧은_secret은_기동_시점에_거부된다() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(30),
                Duration.ofDays(14), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
