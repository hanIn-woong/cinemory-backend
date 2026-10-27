package com.project.cinemory.domain.boxoffice;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.domain.boxoffice.scheduler.BoxOfficeScheduler;
import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.domain.movie.service.SeedResult;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbSearchResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 박스오피스 미등록 영화 자동 등록(cinemap-spec C-6-2) — 스케줄 대상은 <b>가장 최근 집계일</b>의 {@code movie_id}가 null인 제목뿐이고,
 * 하루 상한이 있으며, 수동 시드와 실행 플래그를 공유한다. TMDB는 목이다(검색 결과 없음 → 건너뜀으로 흘려 호출 대상만 본다).
 */
@SpringBootTest
@Transactional
class BoxOfficeReverseSeedScheduleTest {

    // 다른 데이터보다 확실히 뒤인 날짜 — "가장 최근 집계일"이 이 테스트의 날짜가 되게 한다
    private static final LocalDate LATEST = LocalDate.of(2099, 1, 2);
    private static final LocalDate PREVIOUS = LocalDate.of(2099, 1, 1);

    @Autowired
    private MovieSeedService movieSeedService;
    @Autowired
    private BoxOfficeScheduler scheduler;
    @Autowired
    private BoxOfficeRecordRepository boxOfficeRecordRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbClient tmdbClient;

    private int serial;

    @BeforeEach
    void setUp() {
        // 시드는 참조 테이블이 비어 있으면 시작하지 않는다 — 테스트 DB가 비어 있어도 돌도록 트랜잭션 안에서만 채운다
        if (genreRepository.count() == 0) {
            genreRepository.save(Genre.of(99_901, "테스트장르"));
        }
        if (countryRepository.count() == 0) {
            countryRepository.save(Country.of("ZZ", "테스트국가"));
        }
        when(tmdbClient.searchMovie(anyString(), any())).thenReturn(new TmdbSearchResponse(List.of()));
    }

    private void record(LocalDate date, RankType type, String title, Movie movie) {
        boxOfficeRecordRepository.save(BoxOfficeRecord.builder()
                .targetDate(date).rankType(type).boxOfficeRank(++serial).rankChange(0).isNew(false)
                .koficMovieCd("T" + serial).movieTitleSnapshot(title).openDate(date.minusDays(10))
                .movie(movie).salesAmount(1L).audienceCount(1).audienceAcc(1L).build());
    }

    @Test
    void 그날_수집분의_미매칭_제목만_대상이다() {
        Movie linked = movieRepository.save(Movie.builder().tmdbId(99_001L).title("연결됨").build());
        record(LATEST, RankType.DAILY, "오늘미매칭A", null);
        record(LATEST, RankType.DAILY, "오늘미매칭B", null);
        record(LATEST, RankType.DAILY, "오늘매칭됨", linked);        // 이미 연결 → 제외
        record(PREVIOUS, RankType.DAILY, "어제미매칭", null);         // 지난 집계일 → 제외(수동 시드의 몫)
        record(PREVIOUS, RankType.WEEKLY, "지난주간미매칭", null);    // 구분이 달라도 집계일이 지났으면 제외(날짜 기준이다)
        em.flush();

        SeedResult result = movieSeedService.seedFromLatestBoxOffice();

        verify(tmdbClient).searchMovie(eq("오늘미매칭A"), any());
        verify(tmdbClient).searchMovie(eq("오늘미매칭B"), any());
        verify(tmdbClient, never()).searchMovie(eq("오늘매칭됨"), any());
        verify(tmdbClient, never()).searchMovie(eq("어제미매칭"), any());
        verify(tmdbClient, never()).searchMovie(eq("지난주간미매칭"), any());
        assertThat(result.skipped()).isEqualTo(2);   // 목이 "검색 결과 없음"을 돌려주므로 둘 다 건너뜀
    }

    @Test
    void 하루_상한을_넘기지_않는다() {
        for (int i = 0; i < 25; i++) {
            record(LATEST, RankType.DAILY, "상한제목" + i, null);
        }
        em.flush();

        movieSeedService.seedFromLatestBoxOffice();

        // reverse-seed-daily-limit 기본 20
        verify(tmdbClient, times(20)).searchMovie(anyString(), any());
    }

    @Test
    void 수동_시드가_실행_중이면_스케줄은_건너뛰고_TMDB를_부르지_않는다() {
        record(LATEST, RankType.DAILY, "오늘미매칭", null);
        em.flush();
        @SuppressWarnings("unchecked")
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(movieSeedService, "running");
        running.set(true);
        try {
            scheduler.reverseSeedUnmatchedMovies();   // 예외 없이 INFO만 남기고 끝나야 한다
        } finally {
            running.set(false);
        }

        verify(tmdbClient, never()).searchMovie(anyString(), any());
    }

    @Test
    void 집계가_없으면_아무것도_하지_않는다() {
        // 일별 집계가 하나도 없는 상태는 다른 데이터에 기대므로, 날짜 지정 조회가 빈 결과를 돌려주는지만 확인한다
        assertThat(boxOfficeRecordRepository.findUnmatchedTitlesOn(LocalDate.of(2098, 1, 1),
                org.springframework.data.domain.PageRequest.of(0, 20))).isEmpty();
    }
}
