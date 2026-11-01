package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.company.entity.Company;
import com.project.cinemory.domain.movie.dto.MovieDetailResponse;
import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieCompany;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영화 상세 보강 (service-layer-spec 4-2-B ④ 테스트 ①~④): 관람등급 · 제작사 상위 3 · 개봉 중일 때만 누적 관객.
 * "오늘"은 서버 {@code Clock}에서 읽어 상대 날짜로 만든다(경계 6일 전/8일 전).
 */
@SpringBootTest
@Transactional
class MovieDetailEnrichmentTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private MovieQueryService movieQueryService;
    @Autowired
    private Clock clock;
    @Autowired
    private EntityManager em;

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(600_000_000L + SEQ.incrementAndGet()).title("상세 보강 영화").build();
        em.persist(movie);
        return movie;
    }

    private void company(Movie movie, String name, int order) {
        Company company = Company.of(800_000_000L + SEQ.incrementAndGet(), name, null, "US");
        em.persist(company);
        em.persist(MovieCompany.of(movie, company, order));
    }

    private void daily(Movie movie, int daysAgo, long audienceAcc) {
        em.persist(BoxOfficeRecord.builder()
                .targetDate(LocalDate.now(clock).minusDays(daysAgo))
                .rankType(RankType.DAILY)
                .boxOfficeRank(1)
                .koficMovieCd("T" + SEQ.incrementAndGet())
                .movieTitleSnapshot("스냅샷")
                .movie(movie)
                .audienceAcc(audienceAcc)
                .build());
    }

    private MovieDetailResponse detail(Movie movie) {
        em.flush();
        em.clear();
        return movieQueryService.getMovieDetail(movie.getId());
    }

    // ---- ① 등급 · 제작사 -------------------------------------------------------------------------

    @Test
    void T1_등급과_제작사가_응답에_담긴다() {
        Movie movie = movie();
        movie.changeKrCertification(KrCertification.AGE_15);
        company(movie, "First Co", 0);
        company(movie, "Second Co", 1);

        MovieDetailResponse response = detail(movie);

        assertThat(response.krCertification()).isEqualTo(KrCertification.AGE_15);
        assertThat(response.companies()).extracting(c -> c.name()).containsExactly("First Co", "Second Co");
    }

    @Test
    void T1_등급과_제작사와_박스오피스가_없으면_null_빈목록_null이다() {
        MovieDetailResponse response = detail(movie());

        assertThat(response.krCertification()).isNull();
        assertThat(response.companies()).isEmpty();
        assertThat(response.boxOffice()).isNull();
    }

    // ---- ② 제작사 상위 3 ---------------------------------------------------------------------------

    @Test
    void T2_제작사_5곳이면_display_order_상위_3개만_내린다() {
        Movie movie = movie();
        // 삽입 순서를 일부러 뒤섞는다 - 정렬이 display_order를 따라야 한다
        company(movie, "E", 4);
        company(movie, "B", 1);
        company(movie, "A", 0);
        company(movie, "D", 3);
        company(movie, "C", 2);

        assertThat(detail(movie).companies()).extracting(c -> c.name()).containsExactly("A", "B", "C");
    }

    // ---- ③ ④ 누적 관객 -----------------------------------------------------------------------------

    @Test
    void T3_6일_전_일별_기록이_있으면_boxOffice가_있고_8일_전뿐이면_null이다() {
        Movie recent = movie();
        daily(recent, 6, 1_000L);
        Movie stale = movie();
        daily(stale, 8, 5_000L);

        MovieDetailResponse recentResponse = detail(recent);
        assertThat(recentResponse.boxOffice()).isNotNull();
        assertThat(recentResponse.boxOffice().audienceAcc()).isEqualTo(1_000L);
        assertThat(recentResponse.boxOffice().targetDate()).isEqualTo(LocalDate.now(clock).minusDays(6));

        assertThat(detail(stale).boxOffice()).isNull();
    }

    @Test
    void T4_같은_영화의_기록이_여럿이면_가장_최근_기록의_누적_관객이다() {
        Movie movie = movie();
        daily(movie, 5, 1_000L);
        daily(movie, 1, 3_000L);
        daily(movie, 3, 2_000L);

        assertThat(detail(movie).boxOffice().audienceAcc()).isEqualTo(3_000L);
    }

    @Test
    void T4_주간_기록만_있으면_개봉_중이_아니다() {
        Movie movie = movie();
        em.persist(BoxOfficeRecord.builder()
                .targetDate(LocalDate.now(clock).minusDays(1))
                .rankType(RankType.WEEKLY)
                .boxOfficeRank(1)
                .koficMovieCd("W" + SEQ.incrementAndGet())
                .movieTitleSnapshot("주간")
                .movie(movie)
                .audienceAcc(9_000L)
                .build());

        assertThat(detail(movie).boxOffice()).isNull();
    }
}
