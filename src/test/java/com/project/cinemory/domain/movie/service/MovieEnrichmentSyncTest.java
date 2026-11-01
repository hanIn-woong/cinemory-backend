package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbProductionCompany;
import com.project.cinemory.global.infra.tmdb.dto.TmdbReleaseDates;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영화 정보 보강 - 관람등급 · 제작사 (tmdb-sync-spec 6-12-6, 테스트 ①~⑦). {@code MovieSyncPersister.persist}를 직접 호출한다
 * (TMDB 호출은 이미 끝난 뒤의 DB 작업만 검증). 제작사 id는 실 시드와 겹치지 않게 큰 값을 쓴다.
 */
@SpringBootTest
@Transactional
class MovieEnrichmentSyncTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MovieSyncPersister persister;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private EntityManager em;

    private TmdbMovieDetailResponse detail(long tmdbId, TmdbReleaseDates releaseDates, List<TmdbProductionCompany> companies) {
        return new TmdbMovieDetailResponse(tmdbId, false, "보강 테스트", "Enrichment Test", null, "2024-01-01",
                null, 100, null, 7.0, 10, null, null, null, null, null, null, null, releaseDates, companies);
    }

    private static TmdbReleaseDates kr(TmdbReleaseDates.Release... releases) {
        return new TmdbReleaseDates(List.of(new TmdbReleaseDates.Country("KR", List.of(releases))));
    }

    private static TmdbReleaseDates.Release release(String certification, Integer type) {
        return new TmdbReleaseDates.Release(certification, type);
    }

    private static TmdbProductionCompany company(long id, String name) {
        return new TmdbProductionCompany(id, name, "/logo" + id + ".png", "US");
    }

    private long newTmdbId() {
        return 700_000_000L + SEQ.incrementAndGet();
    }

    private long newCompanyId() {
        return 900_000_000L + SEQ.incrementAndGet();
    }

    /** movie_company를 display_order 순으로 읽은 회사 이름. */
    private List<String> companyNames(Long movieId) {
        em.flush();
        return em.createQuery("select mc.company.name from MovieCompany mc where mc.movie.id = :id order by mc.displayOrder", String.class)
                .setParameter("id", movieId)
                .getResultList();
    }

    // ---- ① ~ ④ 관람등급 -----------------------------------------------------------------------------

    @Test
    void T1_KR_등급_15는_AGE_15로_저장된다() {
        Movie saved = persister.persist(detail(newTmdbId(), kr(release("15", 3)), null));

        em.flush();
        em.clear();
        assertThat(movieRepository.findById(saved.getId()).orElseThrow().getKrCertification()).isEqualTo(KrCertification.AGE_15);
    }

    @Test
    void T1_표기는_대소문자와_공백을_무시해_정규화된다() {
        assertThat(KrCertification.fromTmdb("All")).isEqualTo(KrCertification.ALL);
        assertThat(KrCertification.fromTmdb("ALL")).isEqualTo(KrCertification.ALL);
        assertThat(KrCertification.fromTmdb(" 12 ")).isEqualTo(KrCertification.AGE_12);
        assertThat(KrCertification.fromTmdb("18")).isEqualTo(KrCertification.AGE_18);
        assertThat(KrCertification.fromTmdb("19")).isEqualTo(KrCertification.AGE_18); // 옛 연소자 관람불가 표기
        assertThat(KrCertification.fromTmdb("Restricted Screening")).isEqualTo(KrCertification.RESTRICTED);
        assertThat(KrCertification.fromTmdb("restrictedscreening")).isEqualTo(KrCertification.RESTRICTED);
    }

    @Test
    void T2_KR_항목이_없으면_null이다() {
        TmdbReleaseDates onlyUs = new TmdbReleaseDates(List.of(
                new TmdbReleaseDates.Country("US", List.of(release("PG-13", 3)))));

        assertThat(persister.persist(detail(newTmdbId(), onlyUs, null)).getKrCertification()).isNull();
        assertThat(persister.persist(detail(newTmdbId(), null, null)).getKrCertification()).isNull();
    }

    @Test
    void T3_극장_개봉_type3과_type4가_둘_다_있으면_type3의_등급을_쓴다() {
        // 응답 순서와 무관하게 type 3 -> 2 -> 그 외 오름차순
        Movie movie = persister.persist(detail(newTmdbId(), kr(release("18", 4), release("12", 3)), null));
        assertThat(movie.getKrCertification()).isEqualTo(KrCertification.AGE_12);

        Movie limited = persister.persist(detail(newTmdbId(), kr(release("18", 4), release("15", 2)), null));
        assertThat(limited.getKrCertification()).isEqualTo(KrCertification.AGE_15);
    }

    @Test
    void T3_등급이_빈_항목은_건너뛰고_비어_있지_않은_첫_값을_고른다() {
        Movie movie = persister.persist(detail(newTmdbId(), kr(release("", 3), release("  ", 3), release("15", 4)), null));

        assertThat(movie.getKrCertification()).isEqualTo(KrCertification.AGE_15);
    }

    @Test
    void T4_모르는_표기는_null이다() {
        Movie movie = persister.persist(detail(newTmdbId(), kr(release("PG-13", 3)), null));

        assertThat(movie.getKrCertification()).isNull();
    }

    @Test
    void T4_재동기화에서_등급이_사라지면_지워진다() {
        long tmdbId = newTmdbId();
        persister.persist(detail(tmdbId, kr(release("15", 3)), null));

        Movie again = persister.persist(detail(tmdbId, null, null));

        assertThat(again.getKrCertification()).isNull();
    }

    // ---- ⑤ ~ ⑦ 제작사 -----------------------------------------------------------------------------

    @Test
    void T5_제작사_3곳이_TMDB_순서대로_저장된다() {
        long a = newCompanyId();
        long b = newCompanyId();
        long c = newCompanyId();

        Movie movie = persister.persist(detail(newTmdbId(), null,
                List.of(company(a, "A Studio"), company(b, "B Pictures"), company(c, "C Films"))));

        assertThat(companyNames(movie.getId())).containsExactly("A Studio", "B Pictures", "C Films");
    }

    @Test
    void T6_재동기화하면_연결이_교체되고_회사_이름_변경이_반영된다() {
        long a = newCompanyId();
        long b = newCompanyId();
        long c = newCompanyId();
        long tmdbId = newTmdbId();
        Movie first = persister.persist(detail(tmdbId, null, List.of(company(a, "A Studio"), company(b, "B Pictures"))));
        assertThat(companyNames(first.getId())).containsExactly("A Studio", "B Pictures");

        Movie second = persister.persist(detail(tmdbId, null, List.of(company(c, "C Films"), company(b, "B Pictures Renamed"))));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(companyNames(second.getId())).containsExactly("C Films", "B Pictures Renamed");
        // company 행 자체는 남는다 - 연결만 교체된다(참조 쪽 RESTRICT)
        Long aRows = em.createQuery("select count(c) from Company c where c.tmdbCompanyId = :id", Long.class)
                .setParameter("id", a).getSingleResult();
        assertThat(aRows).isEqualTo(1L);
    }

    @Test
    void T6_제작사가_사라진_영화는_재동기화하면_연결이_비워진다() {
        long tmdbId = newTmdbId();
        Movie first = persister.persist(detail(tmdbId, null, List.of(company(newCompanyId(), "Gone Co"))));
        assertThat(companyNames(first.getId())).hasSize(1);

        persister.persist(detail(tmdbId, null, null));

        assertThat(companyNames(first.getId())).isEmpty();
    }

    @Test
    void T7_같은_회사가_여러_영화에_있으면_company_행은_하나다() {
        long shared = newCompanyId();

        Movie one = persister.persist(detail(newTmdbId(), null, List.of(company(shared, "Shared Co"))));
        Movie two = persister.persist(detail(newTmdbId(), null, List.of(company(shared, "Shared Co"))));

        Long rows = em.createQuery("select count(c) from Company c where c.tmdbCompanyId = :id", Long.class)
                .setParameter("id", shared).getSingleResult();
        assertThat(rows).isEqualTo(1L);
        assertThat(companyNames(one.getId())).containsExactly("Shared Co");
        assertThat(companyNames(two.getId())).containsExactly("Shared Co");
    }

    @Test
    void 영화_내_중복_회사_id는_하나로_접히고_빈_origin_country는_null이다() {
        long id = newCompanyId();
        TmdbProductionCompany blankCountry = new TmdbProductionCompany(id, "Dup Co", null, "");

        Movie movie = persister.persist(detail(newTmdbId(), null, List.of(blankCountry, blankCountry)));

        assertThat(companyNames(movie.getId())).containsExactly("Dup Co");
        String country = em.createQuery("select c.originCountry from Company c where c.tmdbCompanyId = :id", String.class)
                .setParameter("id", id).getSingleResult();
        assertThat(country).isNull();
    }

    // ---- JSON 매핑 ---------------------------------------------------------------------------------

    @Test
    void TMDB_응답_JSON의_release_dates와_production_companies가_매핑된다() {
        String json = """
                {"id": 1, "title": "t", "original_title": "o",
                 "production_companies": [{"id": 7, "name": "Studio", "logo_path": "/l.png", "origin_country": "KR"}],
                 "release_dates": {"results": [
                   {"iso_3166_1": "US", "release_dates": [{"certification": "R", "type": 3}]},
                   {"iso_3166_1": "KR", "release_dates": [{"certification": "18", "type": 3}, {"certification": "", "type": 1}]}
                 ]}}
                """;

        TmdbMovieDetailResponse parsed = JSON.readValue(json, TmdbMovieDetailResponse.class);

        assertThat(parsed.krCertificationRaw()).isEqualTo("18");
        assertThat(parsed.productionCompaniesOrEmpty()).extracting(TmdbProductionCompany::name).containsExactly("Studio");
        assertThat(parsed.productionCompaniesOrEmpty().get(0).originCountry()).isEqualTo("KR");
    }
}
