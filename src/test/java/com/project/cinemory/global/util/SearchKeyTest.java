package com.project.cinemory.global.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchKeyTest {

    @Test
    void 공백과_문장부호와_기호를_지우고_소문자로_만든다() {
        assertThat(SearchKey.normalize("그대, 별처럼")).isEqualTo("그대별처럼");
        assertThat(SearchKey.normalize("Spider-Man: No Way Home")).isEqualTo("spidermannowayhome");
        assertThat(SearchKey.normalize("  Mission: Impossible – Fallout! ")).isEqualTo("missionimpossiblefallout");
        assertThat(SearchKey.normalize("서울의 봄 · 2023 & 다시")).isEqualTo("서울의봄2023다시");
    }

    @Test
    void NFKC로_전각_문자와_호환_문자를_맞춘다() {
        assertThat(SearchKey.normalize("ＡＢＣ１２３")).isEqualTo("abc123");
        assertThat(SearchKey.normalize("Ⅱ")).isEqualTo("ii");
    }

    @Test
    void 문장부호만_있거나_null이면_빈_문자열이다() {
        assertThat(SearchKey.normalize("!?,. -~")).isEmpty();
        assertThat(SearchKey.normalize("   ")).isEmpty();
        assertThat(SearchKey.normalize(null)).isEmpty();
    }

    @Test
    void 한글_한_음절은_그대로_남는다() {
        assertThat(SearchKey.normalize("별")).isEqualTo("별");
    }
}
