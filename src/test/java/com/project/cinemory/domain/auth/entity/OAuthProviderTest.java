package com.project.cinemory.domain.auth.entity;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 경로 변수 → 제공자 변환 (account-integrity D-5-D). */
class OAuthProviderTest {

    @ParameterizedTest
    @ValueSource(strings = {"google", "GOOGLE", "Google"})
    void google은_대소문자와_무관하게_GOOGLE이다(String value) {
        assertThat(OAuthProvider.from(value)).isEqualTo(OAuthProvider.GOOGLE);
    }

    @Test
    void kakao는_KAKAO다() {
        assertThat(OAuthProvider.from("kakao")).isEqualTo(OAuthProvider.KAKAO);
    }

    /** 검증기가 없는 제공자는 enum에 없어야 한다 — 네이버는 착수 때 검증기와 함께 추가한다. */
    @Test
    void 지원하지_않는_제공자는_UNSUPPORTED_OAUTH_PROVIDER다() {
        assertThatThrownBy(() -> OAuthProvider.from("naver"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }
}
