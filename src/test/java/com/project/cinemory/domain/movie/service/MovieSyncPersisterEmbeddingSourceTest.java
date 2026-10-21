package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.keyword.entity.Keyword;
import com.project.cinemory.domain.keyword.repository.KeywordRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.infra.tmdb.dto.TmdbKeywords;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbTranslations;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임베딩 입력용 보강(tmdb-sync-spec 6-11)의 영속화 — tagline·overview_en 절단, 키워드 upsert·교체·rename.
 * {@code MovieSyncPersister.persist}를 직접 호출한다(TMDB 호출은 이미 끝난 뒤의 DB 작업만 검증).
 */
@SpringBootTest
@Transactional
class MovieSyncPersisterEmbeddingSourceTest {

    private static final long TMDB_ID = 98_801L;
    private static final long KW_A = 9_880_001L;
    private static final long KW_B = 9_880_002L;
    private static final long KW_C = 9_880_003L;

    @Autowired
    private MovieSyncPersister persister;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private KeywordRepository keywordRepository;
    @Autowired
    private EntityManager em;

    private TmdbMovieDetailResponse detail(String tagline, List<TmdbKeywords.Item> keywords, String overviewEn) {
        TmdbTranslations translations = overviewEn == null ? null : new TmdbTranslations(List.of(
                new TmdbTranslations.Item("en", "US", new TmdbTranslations.Data(overviewEn))));
        return new TmdbMovieDetailResponse(TMDB_ID, false, "임베딩 테스트", "Embedding Test", null, "2024-01-01",
                null, 100, null, 7.0, 10, null, null, null, null,
                tagline, keywords == null ? null : new TmdbKeywords(keywords), translations);
    }

    private static TmdbKeywords.Item kw(long id, String name) {
        return new TmdbKeywords.Item(id, name);
    }

    private List<Long> mappedKeywordIds(Long movieId) {
        em.flush();
        return em.createQuery(
                        "select mk.keyword.tmdbKeywordId from MovieKeyword mk where mk.movie.id = :id order by mk.keyword.tmdbKeywordId",
                        Long.class)
                .setParameter("id", movieId)
                .getResultList();
    }

    @Test
    void 신규_영화에_tagline_overview_en_키워드가_저장된다() {
        Movie saved = persister.persist(detail("한 줄 카피", List.of(kw(KW_A, "heist"), kw(KW_B, "twist")), "English plot"));

        em.flush();
        em.clear();
        Movie reloaded = movieRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTagline()).isEqualTo("한 줄 카피");
        assertThat(reloaded.getOverviewEn()).isEqualTo("English plot");
        assertThat(mappedKeywordIds(saved.getId())).containsExactly(KW_A, KW_B);
    }

    @Test
    void overview_en은_한국어_줄거리가_없어도_저장된다() {
        // detail()의 overview는 null — ko 줄거리가 없는 경우
        Movie saved = persister.persist(detail(null, null, "English plot"));

        assertThat(saved.getOverview()).isNull();
        assertThat(saved.getOverviewEn()).isEqualTo("English plot");
        assertThat(saved.getTagline()).isNull();
    }

    @Test
    void 재동기화하면_키워드가_교체되고_이전_키워드는_매핑에서_빠진다() {
        Movie first = persister.persist(detail(null, List.of(kw(KW_A, "heist"), kw(KW_B, "twist")), null));
        assertThat(mappedKeywordIds(first.getId())).containsExactly(KW_A, KW_B);

        Movie second = persister.persist(detail(null, List.of(kw(KW_B, "twist"), kw(KW_C, "revenge")), null));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(mappedKeywordIds(second.getId())).containsExactly(KW_B, KW_C);
        // keyword 행 자체는 남는다 — 매핑만 교체된다(참조 쪽 RESTRICT).
        assertThat(keywordRepository.findByTmdbKeywordIdIn(List.of(KW_A))).hasSize(1);
    }

    @Test
    void 키워드가_사라진_영화는_재동기화하면_매핑이_비워진다() {
        Movie first = persister.persist(detail(null, List.of(kw(KW_A, "heist")), null));
        assertThat(mappedKeywordIds(first.getId())).containsExactly(KW_A);

        persister.persist(detail(null, null, null));

        assertThat(mappedKeywordIds(first.getId())).isEmpty();
    }

    @Test
    void 영화_내_중복_키워드_id는_하나로_접힌다() {
        Movie saved = persister.persist(detail(null, List.of(kw(KW_A, "heist"), kw(KW_A, "heist"), kw(KW_B, "twist")), null));

        assertThat(mappedKeywordIds(saved.getId())).containsExactly(KW_A, KW_B);
        assertThat(keywordRepository.findByTmdbKeywordIdIn(List.of(KW_A))).hasSize(1);
    }

    @Test
    void 기존_키워드의_이름이_바뀌면_같은_행이_rename된다() {
        Keyword existing = keywordRepository.saveAndFlush(Keyword.of(KW_A, "old name"));

        Movie saved = persister.persist(detail(null, List.of(kw(KW_A, "new name")), null));

        em.flush();
        em.clear();
        List<Keyword> reloaded = keywordRepository.findByTmdbKeywordIdIn(List.of(KW_A));
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).getId()).isEqualTo(existing.getId());
        assertThat(reloaded.get(0).getName()).isEqualTo("new name");
        assertThat(mappedKeywordIds(saved.getId())).containsExactly(KW_A);
    }

    @Test
    void id나_이름이_비어_있는_키워드는_건너뛴다() {
        Movie saved = persister.persist(detail(null,
                List.of(kw(KW_A, "heist"), new TmdbKeywords.Item(null, "no id"), kw(KW_B, " ")), null));

        assertThat(mappedKeywordIds(saved.getId())).containsExactly(KW_A);
    }

    @Test
    void tagline이_255자를_넘으면_절단되고_overview_en이_1000자를_넘으면_절단된다() {
        Movie saved = persister.persist(detail("가".repeat(300), null, "e".repeat(1500)));

        assertThat(saved.getTagline()).hasSize(255).endsWith("...");
        assertThat(saved.getOverviewEn()).hasSize(1000).endsWith("...");
    }

    @Test
    void 상한_이하인_tagline과_overview_en은_그대로_저장된다() {
        Movie saved = persister.persist(detail("가".repeat(255), null, "e".repeat(1000)));

        assertThat(saved.getTagline()).hasSize(255).doesNotEndWith("...");
        assertThat(saved.getOverviewEn()).hasSize(1000).doesNotEndWith("...");
    }

    @Test
    void 재동기화가_tagline과_overview_en을_갱신하고_사라지면_null이_된다() {
        persister.persist(detail("처음 카피", null, "first"));

        Movie updated = persister.persist(detail(null, null, null));

        assertThat(updated.getTagline()).isNull();
        assertThat(updated.getOverviewEn()).isNull();
    }
}
