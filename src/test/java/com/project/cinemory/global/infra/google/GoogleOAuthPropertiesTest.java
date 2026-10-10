package com.project.cinemory.global.infra.google;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정 누락을 기동 시점에 잡는지 고정한다 ({@code KakaoOAuthPropertiesTest}와 같은 규칙, account-integrity D-5-D).
 *
 * <p>{@code allowedAudiences}가 비면 {@code aud} 검증이 조용히 무력화되고,
 * {@code issuers}가 비면 모든 구글 토큰이 조용히 거부된다. 둘 다 기동 실패가 낫다.
 */
class GoogleOAuthPropertiesTest {

    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");
    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> AUDIENCES = List.of("web-client-id");
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    @Test
    void 정상_설정은_생성된다() {
        assertThatCode(() -> new GoogleOAuthProperties(ISSUERS, JWKS_URI, AUDIENCES, COOLDOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void issuers가_비면_거부된다() {
        assertThatThrownBy(() -> new GoogleOAuthProperties(null, JWKS_URI, AUDIENCES, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOAuthProperties(List.of(), JWKS_URI, AUDIENCES, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOAuthProperties(List.of(" "), JWKS_URI, AUDIENCES, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** <b>aud 검증이 무력화되는 지점.</b> 비어 있으면 반드시 기동에 실패해야 한다. */
    @Test
    void allowedAudiences가_비면_거부된다() {
        assertThatThrownBy(() -> new GoogleOAuthProperties(ISSUERS, JWKS_URI, null, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOAuthProperties(ISSUERS, JWKS_URI, List.of(), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOAuthProperties(ISSUERS, JWKS_URI, List.of("web-client-id", ""), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jwksUri가_비면_거부된다() {
        assertThatThrownBy(() -> new GoogleOAuthProperties(ISSUERS, " ", AUDIENCES, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 쿨다운이_null이거나_음수면_거부된다() {
        assertThatThrownBy(() -> new GoogleOAuthProperties(ISSUERS, JWKS_URI, AUDIENCES, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleOAuthProperties(ISSUERS, JWKS_URI, AUDIENCES, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
