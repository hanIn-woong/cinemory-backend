package com.project.cinemory.global.infra.tmdb.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 임베딩 입력용 헬퍼 3종(tmdb-sync-spec 6-11) — 빈 값·null 정규화와 영문 줄거리 선택 규칙. */
class TmdbMovieDetailEmbeddingHelpersTest {

    private static TmdbMovieDetailResponse detail(String tagline, TmdbKeywords keywords, TmdbTranslations translations) {
        return new TmdbMovieDetailResponse(1L, false, "t", "t", null, null, null, null, null, null, null,
                null, null, null, null, tagline, keywords, translations);
    }

    private static TmdbTranslations.Item item(String lang, String country, String overview) {
        return new TmdbTranslations.Item(lang, country, new TmdbTranslations.Data(overview));
    }

    @Test
    void taglineOrNull은_null과_빈_문자열과_공백을_null로_접는다() {
        assertThat(detail(null, null, null).taglineOrNull()).isNull();
        assertThat(detail("", null, null).taglineOrNull()).isNull();
        assertThat(detail("  ", null, null).taglineOrNull()).isNull();
        assertThat(detail("한 줄 카피", null, null).taglineOrNull()).isEqualTo("한 줄 카피");
    }

    @Test
    void keywordsOrEmpty는_객체_자체나_목록이_null이어도_빈_목록이다() {
        assertThat(detail(null, null, null).keywordsOrEmpty()).isEmpty();
        assertThat(detail(null, new TmdbKeywords(null), null).keywordsOrEmpty()).isEmpty();
        assertThat(detail(null, new TmdbKeywords(List.of(new TmdbKeywords.Item(1L, "a"))), null).keywordsOrEmpty())
                .hasSize(1);
    }

    @Test
    void englishOverview는_translations가_없거나_비어_있으면_null이다() {
        assertThat(detail(null, null, null).englishOverview()).isNull();
        assertThat(detail(null, null, new TmdbTranslations(null)).englishOverview()).isNull();
        assertThat(detail(null, null, new TmdbTranslations(List.of())).englishOverview()).isNull();
    }

    @Test
    void englishOverview는_en_번역이_없으면_null이다() {
        TmdbTranslations translations = new TmdbTranslations(List.of(
                item("ko", "KR", "한국어"), item("fr", "FR", "français")));
        assertThat(detail(null, null, translations).englishOverview()).isNull();
    }

    @Test
    void englishOverview는_en이어도_줄거리가_비었으면_null이다() {
        TmdbTranslations translations = new TmdbTranslations(List.of(
                item("en", "US", ""), item("en", "GB", null),
                new TmdbTranslations.Item("en", "AU", null)));
        assertThat(detail(null, null, translations).englishOverview()).isNull();
    }

    @Test
    void englishOverview는_en이_여럿이면_US를_우선한다() {
        TmdbTranslations translations = new TmdbTranslations(List.of(
                item("en", "GB", "british"), item("en", "US", "american"), item("en", "AU", "australian")));
        assertThat(detail(null, null, translations).englishOverview()).isEqualTo("american");
    }

    @Test
    void englishOverview는_US가_없으면_첫_번째_en을_쓴다() {
        TmdbTranslations translations = new TmdbTranslations(List.of(
                item("fr", "FR", "français"), item("en", "GB", "british"), item("en", "AU", "australian")));
        assertThat(detail(null, null, translations).englishOverview()).isEqualTo("british");
    }

    @Test
    void englishOverview는_US_줄거리가_비면_줄거리가_있는_다른_en으로_넘어간다() {
        TmdbTranslations translations = new TmdbTranslations(List.of(
                item("en", "US", ""), item("en", "GB", "british")));
        assertThat(detail(null, null, translations).englishOverview()).isEqualTo("british");
    }
}
