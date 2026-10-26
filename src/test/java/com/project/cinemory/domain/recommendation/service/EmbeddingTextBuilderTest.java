package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.entity.OverviewSource;
import com.project.cinemory.domain.recommendation.service.EmbeddingTextBuilder.Actor;
import com.project.cinemory.domain.recommendation.service.EmbeddingTextBuilder.Country;
import com.project.cinemory.domain.recommendation.service.EmbeddingTextBuilder.Input;
import com.project.cinemory.domain.recommendation.service.EmbeddingTextBuilder.Named;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingTextBuilderTest {

    private static Input full() {
        return new Input(
                "기생충", "Parasite", LocalDate.of(2019, 5, 30),
                List.of(new Country(2, new BigDecimal("0.200"), "미국"), new Country(1, new BigDecimal("0.800"), "한국")),
                List.of(new Named(2, "스릴러"), new Named(1, "코미디")),
                132,
                List.of(new Named(1, "봉준호")),
                List.of(new Actor(2, 30, "최우식"), new Actor(0, 10, "송강호"), new Actor(1, 20, "이선균"), new Actor(3, 40, "조여정")),
                "모두가 기생하는 세상",
                List.of(new Named(2, "class"), new Named(1, "poverty")),
                "전원 백수인 기택 가족...",
                "A poor family...");
    }

    @Test
    void 템플릿_전체_줄이_순서대로_조립된다() {
        EmbeddingText text = EmbeddingTextBuilder.build(full());

        assertThat(text.sourceText()).isEqualTo(String.join("\n",
                "title: 기생충 (Parasite) | text: 개봉: 2019년 · 국가: 한국, 미국",
                "장르: 코미디, 스릴러",
                "감독: 봉준호",
                "주연: 송강호, 이선균, 최우식",
                "한 줄 소개: 모두가 기생하는 세상",
                "키워드: poverty, class",
                "줄거리: 전원 백수인 기택 가족..."));
        assertThat(text.overviewSource()).isEqualTo(OverviewSource.KO);
    }

    @Test
    void 원제가_표시제목과_같으면_괄호를_생략한다() {
        Input in = new Input("Parasite", "Parasite", null, List.of(), List.of(), null, List.of(), List.of(), null, List.of(), null, null);

        assertThat(EmbeddingTextBuilder.build(in).title()).isEqualTo("Parasite");
    }

    @Test
    void 값이_없는_줄은_줄째_생략된다() {
        Input in = new Input("제목", null, null, List.of(), List.of(), null, List.of(), List.of(), "  ", List.of(), null, null);

        EmbeddingText text = EmbeddingTextBuilder.build(in);

        assertThat(text.body()).isEmpty();
        assertThat(text.sourceText()).isEqualTo("title: 제목 | text: ");
        assertThat(text.overviewSource()).isEqualTo(OverviewSource.NONE);
    }

    @Test
    void 개봉일만_있거나_국가만_있어도_줄이_만들어진다() {
        Input onlyYear = new Input("a", null, LocalDate.of(2000, 1, 1), List.of(), List.of(), null, List.of(), List.of(), null, List.of(), null, null);
        Input onlyCountry = new Input("a", null, null, List.of(new Country(1, BigDecimal.ONE, "일본")), List.of(), null, List.of(), List.of(), null, List.of(), null, null);

        assertThat(EmbeddingTextBuilder.build(onlyYear).body()).isEqualTo("개봉: 2000년");
        assertThat(EmbeddingTextBuilder.build(onlyCountry).body()).isEqualTo("국가: 일본");
    }

    @Test
    void 줄거리_우선순위는_한국어_영문_없음이다() {
        Input ko = withOverview("한국어", "English");
        Input en = withOverview(null, "English");
        Input blankKo = withOverview("   ", "English");
        Input none = withOverview(null, "");

        assertThat(EmbeddingTextBuilder.build(ko).overviewSource()).isEqualTo(OverviewSource.KO);
        assertThat(EmbeddingTextBuilder.build(ko).body()).isEqualTo("줄거리: 한국어");
        assertThat(EmbeddingTextBuilder.build(en).overviewSource()).isEqualTo(OverviewSource.EN);
        assertThat(EmbeddingTextBuilder.build(en).body()).isEqualTo("줄거리: English");
        assertThat(EmbeddingTextBuilder.build(blankKo).overviewSource()).isEqualTo(OverviewSource.EN);
        assertThat(EmbeddingTextBuilder.build(none).overviewSource()).isEqualTo(OverviewSource.NONE);
        assertThat(EmbeddingTextBuilder.build(none).body()).isEmpty();
    }

    @Test
    void 상영시간_라벨은_95분_이하_짧은_편_140분_이상_긴_편_그_사이는_생략() {
        assertThat(runtimeLine(88)).isEqualTo("상영시간: 짧은 편 (88분)");
        assertThat(runtimeLine(95)).isEqualTo("상영시간: 짧은 편 (95분)");
        assertThat(runtimeLine(96)).isNull();
        assertThat(runtimeLine(139)).isNull();
        assertThat(runtimeLine(140)).isEqualTo("상영시간: 긴 편 (140분)");
        assertThat(runtimeLine(152)).isEqualTo("상영시간: 긴 편 (152분)");
        assertThat(runtimeLine(null)).isNull();
    }

    @Test
    void 주연은_상위_3명_키워드는_최대_20개() {
        List<Actor> actors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            actors.add(new Actor(i, 100 + i, "배우" + i));
        }
        List<Named> keywords = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            keywords.add(new Named(i, "k" + i));
        }
        Input in = new Input("t", null, null, List.of(), List.of(), null, List.of(), actors, null, keywords, null, null);

        String body = EmbeddingTextBuilder.build(in).body();

        assertThat(body).contains("주연: 배우0, 배우1, 배우2").doesNotContain("배우3");
        assertThat(body).contains("k20").doesNotContain("k21");
    }

    @Test
    void 입력_순서를_섞어도_같은_텍스트가_나온다() {
        String expected = EmbeddingTextBuilder.build(full()).sourceText();
        Input base = full();
        Random random = new Random(42);

        for (int i = 0; i < 50; i++) {
            Input shuffled = new Input(
                    base.displayTitle(), base.originalTitle(), base.releaseDate(),
                    shuffled(base.countries(), random), shuffled(base.genres(), random), base.runtime(),
                    shuffled(base.directors(), random), shuffled(base.actors(), random), base.tagline(),
                    shuffled(base.keywords(), random), base.overview(), base.overviewEn());

            assertThat(EmbeddingTextBuilder.build(shuffled).sourceText()).isEqualTo(expected);
        }
    }

    @Test
    void 같은_입력이면_해시가_같고_모델명이_다르면_해시가_다르다() {
        EmbeddingText text = EmbeddingTextBuilder.build(full());

        assertThat(text.hash("model-a")).isEqualTo(EmbeddingTextBuilder.build(full()).hash("model-a"));
        assertThat(text.hash("model-a")).isNotEqualTo(text.hash("model-b")).hasSize(64);
    }

    @Test
    void source_text는_4000자를_넘지_않는다() {
        Input in = withOverview("가".repeat(5000), null);

        assertThat(EmbeddingTextBuilder.build(in).sourceText().length()).isLessThanOrEqualTo(4000);
    }

    private static String runtimeLine(Integer runtime) {
        Input in = new Input("t", null, null, List.of(), List.of(), runtime, List.of(), List.of(), null, List.of(), null, null);
        return EmbeddingTextBuilder.build(in).body().isEmpty() ? null : EmbeddingTextBuilder.build(in).body();
    }

    private static Input withOverview(String ko, String en) {
        return new Input("t", null, null, List.of(), List.of(), null, List.of(), List.of(), null, List.of(), ko, en);
    }

    private static <T> List<T> shuffled(List<T> list, Random random) {
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy, random);
        return copy;
    }
}
