package com.project.cinemory.global.infra.kakao;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정 오타·누락을 런타임이 아니라 <b>기동 시점에</b> 잡는지 고정한다
 * ({@code JwtProperties}의 secret 검증과 같은 규칙).
 *
 * <p>특히 {@code allowedAudiences}가 비면 <b>{@code aud} 검증이 조용히 무력화</b>된다 —
 * 다른 서비스용으로 발급된 카카오 토큰도 서명·{@code iss}·{@code exp}를 전부 통과하게 된다.
 * 조용히 뚫리느니 기동에 실패하는 편이 낫다.
 */
class KakaoOAuthPropertiesTest {

    private static final String ISSUER = "https://kauth.kakao.com";
    private static final String JWKS_URI = "https://kauth.kakao.com/.well-known/jwks.json";
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    private KakaoOAuthProperties create(String issuer, String jwksUri,
                                        List<String> audiences, Duration cooldown) {
        return new KakaoOAuthProperties(issuer, jwksUri, audiences, cooldown);
    }

    @Test
    void 정상_설정은_생성된다() {
        assertThatCode(() -> create(ISSUER, JWKS_URI, List.of("native-app-key"), COOLDOWN))
                .doesNotThrowAnyException();
    }

    /** 웹 로그인을 추가할 때 키만 늘리면 되도록 목록으로 둔다. */
    @Test
    void 여러_audience를_허용한다() {
        assertThatCode(() -> create(ISSUER, JWKS_URI, List.of("native-app-key", "rest-api-key"), COOLDOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void issuer가_비면_거부된다() {
        assertThatThrownBy(() -> create(null, JWKS_URI, List.of("k"), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("  ", JWKS_URI, List.of("k"), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jwksUri가_비면_거부된다() {
        assertThatThrownBy(() -> create(ISSUER, null, List.of("k"), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(ISSUER, "", List.of("k"), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** <b>aud 검증이 무력화되는 지점.</b> 비어 있으면 반드시 기동에 실패해야 한다. */
    @Test
    void allowedAudiences가_비면_거부된다() {
        assertThatThrownBy(() -> create(ISSUER, JWKS_URI, null, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(ISSUER, JWKS_URI, List.of(), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 목록에 빈 문자열이 섞여 있으면 그 항목은 어떤 aud와도 맞지 않는다 — 설정 실수다. */
    @Test
    void allowedAudiences에_공백_항목이_있으면_거부된다() {
        assertThatThrownBy(() -> create(ISSUER, JWKS_URI, List.of("native-app-key", " "), COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 쿨다운이_null이거나_음수면_거부된다() {
        assertThatThrownBy(() -> create(ISSUER, JWKS_URI, List.of("k"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(ISSUER, JWKS_URI, List.of("k"), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 0은 허용한다 — "쿨다운 없음"을 의도적으로 선택할 수 있어야 한다(테스트/로컬). */
    @Test
    void 쿨다운_0은_허용된다() {
        assertThatCode(() -> create(ISSUER, JWKS_URI, List.of("k"), Duration.ZERO))
                .doesNotThrowAnyException();
    }
}
