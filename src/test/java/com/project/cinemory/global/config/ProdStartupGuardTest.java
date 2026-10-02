package com.project.cinemory.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * deploy-spec 1-2 B — 운영 기동 가드. 실제 prod 기동 검증은 1-5 #3·#4(jar 실행)에서 한다.
 */
class ProdStartupGuardTest {

    private static final String LIST = ProdStartupGuard.REQUIRED_PROPERTIES_KEY;

    private static MockEnvironment requiring(String... keys) {
        MockEnvironment env = new MockEnvironment();
        for (int i = 0; i < keys.length; i++) {
            env.setProperty(LIST + "[" + i + "]", keys[i]);
        }
        return env;
    }

    private static String otherZoneThanSystem() {
        return ZoneId.systemDefault().getId().equals("UTC") ? "Asia/Seoul" : "UTC";
    }

    @Test
    void 목록과_시간대가_비어_있으면_통과한다() {
        assertThatCode(() -> new ProdStartupGuard(new MockEnvironment()).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void 필수_설정이_모두_있으면_통과한다() {
        MockEnvironment env = requiring("jwt.secret", "kofic.api-key");
        env.setProperty("jwt.secret", "value-1");
        env.setProperty("kofic.api-key", "value-2");

        assertThatCode(() -> new ProdStartupGuard(env).verify()).doesNotThrowAnyException();
    }

    @Test
    void 미해석_플레이스홀더면_실패하고_키_이름이_메시지에_포함된다() {
        MockEnvironment env = requiring("kofic.api-key");
        env.setProperty("kofic.api-key", "${KOFIC_API_KEY}");

        assertThatThrownBy(() -> new ProdStartupGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[필수 설정 누락]")
                .hasMessageContaining("kofic.api-key");
    }

    @Test
    void 두_개가_누락되면_둘_다_메시지에_포함된다() {
        MockEnvironment env = requiring("kofic.api-key", "oauth.kakao.allowed-audiences");
        env.setProperty("kofic.api-key", "${KOFIC_API_KEY}");
        // 키 자체가 없는 경우도 누락이다

        assertThatThrownBy(() -> new ProdStartupGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("미충족 2건")
                .hasMessageContaining("kofic.api-key")
                .hasMessageContaining("oauth.kakao.allowed-audiences");
    }

    @Test
    void 값이_공백이면_실패한다() {
        MockEnvironment env = requiring("mail.password-reset.from");
        env.setProperty("mail.password-reset.from", "   ");

        assertThatThrownBy(() -> new ProdStartupGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail.password-reset.from");
    }

    @Test
    void 메시지에_값이_들어가지_않는다() {
        MockEnvironment env = requiring("jwt.secret", "kofic.api-key");
        env.setProperty("jwt.secret", "${JWT_SECRET}-super-secret-suffix");
        env.setProperty("kofic.api-key", "   ");

        assertThatThrownBy(() -> new ProdStartupGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("super-secret-suffix")
                .hasMessageNotContaining("JWT_SECRET");
    }

    @Test
    void 시간대_불일치와_설정_누락이_한_예외에_함께_담긴다() {
        MockEnvironment env = requiring("kofic.api-key");
        env.setProperty(ProdStartupGuard.REQUIRED_TIME_ZONE_KEY, otherZoneThanSystem());

        assertThatThrownBy(() -> new ProdStartupGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("미충족 2건")
                .hasMessageContaining("[시간대]")
                .hasMessageContaining("-Duser.timezone=")
                .hasMessageContaining("kofic.api-key");
    }

    @Test
    void 시간대가_일치하면_통과한다() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(ProdStartupGuard.REQUIRED_TIME_ZONE_KEY, ZoneId.systemDefault().getId());

        assertThatCode(() -> new ProdStartupGuard(env).verify()).doesNotThrowAnyException();
    }
}
