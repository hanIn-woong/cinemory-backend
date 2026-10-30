package com.project.cinemory.domain.user.nickname;

import com.project.cinemory.domain.user.service.NicknameGenerator;
import com.project.cinemory.domain.user.service.NicknamePolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 닉네임 규칙 단위 (profile-spec PR-6). 위반 응답 모양은 {@code NicknameApiTest}. */
class NicknamePolicyTest {

    @Test
    void 앞뒤_공백을_제거한_값을_돌려준다() {
        assertThat(NicknamePolicy.validate("  영화광  ")).isEqualTo("영화광");
    }

    @Test
    void 경계_2자와_20자는_통과한다() {
        assertThat(NicknamePolicy.validate("가나")).isEqualTo("가나");
        assertThat(NicknamePolicy.validate("가".repeat(20))).hasSize(20);
    }

    /** 길이는 코드포인트로 센다 — 이모지 한 글자는 UTF-16으로 2칸이라 char 길이로 세면 10글자 닉네임이 막힌다. */
    @Test
    void 길이는_코드포인트로_센다() {
        String twentyEmoji = "😀".repeat(20);
        assertThat(twentyEmoji.length()).isEqualTo(40);
        assertThat(NicknamePolicy.validate(twentyEmoji)).isEqualTo(twentyEmoji);
        assertInvalid("😀".repeat(21));
        assertInvalid("😀");
    }

    @Test
    void 한_글자와_스물한_글자는_거부한다() {
        assertInvalid("가");
        assertInvalid("가".repeat(21));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "      ", "\t \n"})
    void 비었거나_공백만이면_거부한다(String raw) {
        assertInvalid(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"영화\n광", "영화\t광", "영화\u0000광", "영화\u007f광"})
    void 제어_문자가_있으면_거부한다(String raw) {
        assertInvalid(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"관리자", "운영자", "씨네모리", "cinemory", "admin", "CineMory팬", "Admin1", "나는ADMIN", "진짜관리자입니다"})
    void 예약어를_포함하면_대소문자와_위치에_관계없이_거부한다(String raw) {
        assertInvalid(raw);
    }

    @Test
    void 예약어와_비슷하기만_한_말은_통과한다() {
        assertThat(NicknamePolicy.validate("관리")).isEqualTo("관리");
        assertThat(NicknamePolicy.validate("cinema")).isEqualTo("cinema");
        assertThat(NicknamePolicy.validate("adm1n")).isEqualTo("adm1n");
    }

    @Test
    void 자동_닉네임은_관객_여섯_자리이고_규칙을_통과한다() {
        NicknameGenerator generator = new NicknameGenerator();
        for (int i = 0; i < 2000; i++) {
            String nickname = generator.generate();
            assertThat(nickname).matches("관객[1-9]\\d{5}");
            assertThat(NicknamePolicy.validate(nickname)).isEqualTo(nickname);
        }
    }

    private static void assertInvalid(String raw) {
        assertThatThrownBy(() -> NicknamePolicy.validate(raw))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
                    assertThat(e.getFieldErrors()).singleElement()
                            .satisfies(fe -> assertThat(fe.field()).isEqualTo("nickname"));
                });
    }
}
