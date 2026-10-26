package com.project.cinemory.domain.recommendation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 형용사 기본형 → 관형형 (칩 문구용). 검색에는 기본형만 쓰므로 틀려도 문구가 어색할 뿐이다. */
class KoreanAdjectiveTest {

    @ParameterizedTest
    @CsvSource({
            // 받침 없음
            "잔인하다,잔인한", "슬프다,슬픈", "크다,큰", "무섭다,무서운", "약하다,약한",
            // ㅂ 불규칙
            "무겁다,무거운", "어렵다,어려운", "가볍다,가벼운", "아름답다,아름다운", "뜨겁다,뜨거운",
            // ㄹ 탈락
            "길다,긴", "멀다,먼", "달다,단",
            // 그 밖의 받침
            "좋다,좋은", "많다,많은", "밝다,밝은", "높다,높은", "어둡다,어두운",
            // 있다/없다
            "재미있다,재미있는", "의미없다,의미없는",
            // ㅂ 받침이지만 규칙 활용
            "좁다,좁은",
    })
    void 기본형을_관형형으로_바꾼다(String dictionaryForm, String expected) {
        assertThat(KoreanAdjective.toAdnominal(dictionaryForm)).isEqualTo(expected);
    }

    @Test
    void 기본형이_아니면_그대로_돌려준다() {
        assertThat(KoreanAdjective.toAdnominal("잔인한 장면")).isEqualTo("잔인한 장면");
        assertThat(KoreanAdjective.toAdnominal("다")).isEqualTo("다");
        assertThat(KoreanAdjective.toAdnominal("")).isEmpty();
        assertThat(KoreanAdjective.toAdnominal(null)).isNull();
        assertThat(KoreanAdjective.toAdnominal("abc다")).isEqualTo("abc다");
    }
}
