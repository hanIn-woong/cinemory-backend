package com.project.cinemory.domain.report.service;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.ott.repository.OttPlatformRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.report.dto.ReportCalendarResponse;
import com.project.cinemory.domain.report.dto.ReportMonthlyResponse;
import com.project.cinemory.domain.report.dto.ReportStatisticsResponse;
import com.project.cinemory.domain.review.dto.ReviewWriteRequest;
import com.project.cinemory.domain.review.service.ReviewService;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-a 리포트(4-8) 집계 검증. 네이티브 쿼리 비중이 커 문법 오류가 컴파일 단계에서 잡히지 않으므로
 * 실 DB({@code cinemory_test})에 데이터를 채워 회귀로 고정한다({@code ReviewRatingFallbackTest}와
 * 같은 근거).
 */
@SpringBootTest
@Transactional
class ReportServiceTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private MovieGenreRepository movieGenreRepository;
    @Autowired
    private MovieCountryRepository movieCountryRepository;
    @Autowired
    private MovieActorRepository movieActorRepository;
    @Autowired
    private MovieDirectorRepository movieDirectorRepository;
    @Autowired
    private OttPlatformRepository ottPlatformRepository;
    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private ReviewService reviewService;

    private User createUser(String email, String nickname) {
        User user = User.createLocal(email, "{noop}password", nickname);
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        return userRepository.save(user);
    }

    private Movie createMovie(long tmdbId, String title, int runtime, LocalDate releaseDate,
                               BigDecimal voteAverage, Integer voteCount) {
        return movieRepository.save(Movie.builder()
                .tmdbId(tmdbId).title(title).runtime(runtime).releaseDate(releaseDate)
                .voteAverage(voteAverage).voteCount(voteCount).posterPath("/" + tmdbId + ".jpg")
                .build());
    }

    private WatchRecordCreateRequest request(Long movieId, LocalDate watchDate, WatchType watchType,
                                              Long ottPlatformId, BigDecimal rating) {
        return new WatchRecordCreateRequest(movieId, watchDate, watchType, null, ottPlatformId, rating, null);
    }

    @Test
    void 누적_통계_월말_캘린더를_집계한다() {
        User user = createUser("report-user@test.com", "리포트유저");

        Genre drama = genreRepository.save(Genre.of(90101, "드라마"));
        Country korea = countryRepository.save(Country.of("K9", "코리아"));
        Person actorOne = personRepository.save(Person.of(90201L, "배우일", null));
        Person directorOne = personRepository.save(Person.of(90202L, "감독일", null));
        Person directorTwo = personRepository.save(Person.of(90203L, "감독이", null));
        OttPlatform netflix = ottPlatformRepository.save(OttPlatform.of("리포트넷플릭스"));

        // 영화 A — 2015년작, 평점 7.0, 단독 연출
        Movie movieA = createMovie(96_001L, "리포트 영화 A", 120,
                LocalDate.of(2015, 6, 1), BigDecimal.valueOf(7.0), 200);
        movieGenreRepository.save(MovieGenre.of(movieA, drama, BigDecimal.ONE));
        movieCountryRepository.save(MovieCountry.of(movieA, korea, BigDecimal.ONE));
        movieActorRepository.save(MovieActor.builder()
                .movie(movieA).person(actorOne).characterName("주인공").displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieDirectorRepository.save(MovieDirector.of(movieA, directorOne));

        // 영화 B — 1985년작(고전), 평점 6.0, 공동 연출(1/N 분배 검증용)
        Movie movieB = createMovie(96_002L, "리포트 영화 B", 100,
                LocalDate.of(1985, 5, 1), BigDecimal.valueOf(6.0), 150);
        movieGenreRepository.save(MovieGenre.of(movieB, drama, BigDecimal.ONE));
        movieDirectorRepository.save(MovieDirector.of(movieB, directorOne));
        movieDirectorRepository.save(MovieDirector.of(movieB, directorTwo));

        // 영화 C — 재관람 + 날짜 미상 기록 검증용, 대중 평점 없음
        Movie movieC = createMovie(96_003L, "리포트 영화 C", 90, LocalDate.of(2022, 1, 1), null, null);

        // 1월 — A(극장, 9.0) / B(OTT, 6.0)
        watchRecordService.addWatchRecord(user.getId(),
                request(movieA.getId(), LocalDate.of(2026, 1, 15), WatchType.THEATER, null, BigDecimal.valueOf(9.0)));
        watchRecordService.addWatchRecord(user.getId(),
                request(movieB.getId(), LocalDate.of(2026, 1, 20), WatchType.OTT, netflix.getId(), BigDecimal.valueOf(6.0)));
        // C 1회차 — 날짜 미상(undatedCount), 이후 대표가 2회차로 넘어간다
        watchRecordService.addWatchRecord(user.getId(),
                request(movieC.getId(), null, WatchType.ETC, null, null));
        // C 2회차 — 2월, 재관람. 관람 방식 미지정(UNSPECIFIED)
        watchRecordService.addWatchRecord(user.getId(),
                request(movieC.getId(), LocalDate.of(2026, 2, 1), null, null, null));

        reviewService.writeReview(user.getId(), movieA.getId(), new ReviewWriteRequest("좋았다"));

        assertStatistics(user);
        assertMonthlyReport(user);
        assertCalendar(user);
        assertInvalidPeriodRejected(user);
    }

    private void assertStatistics(User user) {
        // 비로그인(viewerId=null) 조회도 허용돼야 한다 — PUBLIC 계정, RA-6
        ReportStatisticsResponse stats = reportService.getStatistics(null, user.getId());

        assertThat(stats.movieCount()).isEqualTo(3);   // A, B, C(고유)
        assertThat(stats.watchCount()).isEqualTo(4);   // A, B, C1, C2
        assertThat(stats.undatedCount()).isEqualTo(1); // C 1회차
        assertThat(stats.totalWatchedMinutes()).isEqualTo(120 + 100 + 90 + 90); // 전 회차 합(C 2회)
        assertThat(stats.averageRating()).isEqualByComparingTo(BigDecimal.valueOf(7.5)); // (9.0+6.0)/2, C는 대표가 null
        assertThat(stats.rewatchCount()).isEqualTo(1); // watchCount(4) - movieCount(3)
        assertThat(stats.rewatchTop()).hasSize(1);
        assertThat(stats.rewatchTop().get(0).title()).isEqualTo("리포트 영화 C");
        assertThat(stats.rewatchTop().get(0).watchCount()).isEqualTo(2);

        assertThat(stats.ratingDistribution()).hasSize(10);
        assertThat(stats.ratingDistribution().stream().filter(r -> r.rating() == 9).findFirst().orElseThrow().count()).isEqualTo(1);
        assertThat(stats.ratingDistribution().stream().filter(r -> r.rating() == 6).findFirst().orElseThrow().count()).isEqualTo(1);

        assertThat(stats.topGenres()).hasSize(1);
        assertThat(stats.topGenres().get(0).name()).isEqualTo("드라마");
        assertThat(stats.topGenres().get(0).score()).isEqualByComparingTo(BigDecimal.valueOf(15.0)); // 9.0+6.0
        assertThat(stats.topGenres().get(0).count()).isEqualTo(2L);

        assertThat(stats.topCountries()).hasSize(1);
        assertThat(stats.topCountries().get(0).score()).isEqualByComparingTo(BigDecimal.valueOf(9.0));

        assertThat(stats.topActors()).hasSize(1);
        assertThat(stats.topActors().get(0).score()).isEqualByComparingTo(BigDecimal.valueOf(4.5)); // 9.0 * 0.5(LEAD)

        assertThat(stats.topDirectors()).hasSize(2);
        var directorOneItem = stats.topDirectors().stream().filter(d -> d.count() == 2L).findFirst().orElseThrow();
        assertThat(directorOneItem.score()).isEqualByComparingTo(BigDecimal.valueOf(12.0)); // 9.0/1 + 6.0/2

        assertThat(stats.watchTypeDistribution()).hasSize(4); // THEATER, OTT, ETC, UNSPECIFIED 전부 한 번씩
        assertThat(stats.watchTypeDistribution().stream().anyMatch(w -> w.watchType().equals("UNSPECIFIED"))).isTrue();

        assertThat(stats.ottPlatformDistribution()).hasSize(1);
        assertThat(stats.ottPlatformDistribution().get(0).name()).isEqualTo("리포트넷플릭스");

        assertThat(stats.classicCount()).isEqualTo(1); // 영화 B(1985)
        assertThat(stats.oldestWatched()).isNotNull();
        assertThat(stats.oldestWatched().title()).isEqualTo("리포트 영화 B");

        assertThat(stats.ratingBiasAverage()).isEqualByComparingTo(BigDecimal.valueOf(1.0)); // ((9-7)+(6-6))/2
        assertThat(stats.mostOverratedByMe()).isNotNull();
        assertThat(stats.mostOverratedByMe().title()).isEqualTo("리포트 영화 A");
        assertThat(stats.mostUnderratedByMe()).isNotNull();
        assertThat(stats.mostUnderratedByMe().title()).isEqualTo("리포트 영화 B");

        long datedRecordCount = stats.weekdayDistribution().stream().mapToLong(w -> w.count()).sum();
        assertThat(datedRecordCount).isEqualTo(3); // A, B, C2 (날짜 있는 회차)

        assertThat(stats.monthlyTrend()).hasSize(2);
        assertThat(stats.monthlyTrend().get(0).year()).isEqualTo(2026);
        assertThat(stats.monthlyTrend().get(0).month()).isEqualTo(1);
        assertThat(stats.monthlyTrend().get(0).watchCount()).isEqualTo(2);
        assertThat(stats.monthlyTrend().get(0).movieCount()).isEqualTo(2);
        assertThat(stats.monthlyTrend().get(1).month()).isEqualTo(2);
        assertThat(stats.monthlyTrend().get(1).watchCount()).isEqualTo(1);

        assertThat(stats.firstRecordDate()).isEqualTo(LocalDate.now());
        assertThat(stats.reviewRate()).isEqualTo(1.0 / 3);
    }

    private void assertMonthlyReport(User user) {
        ReportMonthlyResponse jan = reportService.getMonthlyReport(user.getId(), user.getId(), 2026, 1);

        assertThat(jan.movieCount()).isEqualTo(2);
        assertThat(jan.watchCount()).isEqualTo(2);
        assertThat(jan.averageRating()).isEqualByComparingTo(BigDecimal.valueOf(7.5));
        assertThat(jan.watchTypeDistribution()).hasSize(2); // THEATER, OTT
        assertThat(jan.mostWatchedDirector()).isNotNull();
        assertThat(jan.mostWatchedDirector().count()).isEqualTo(2L); // 감독일 — A, B 둘 다 출연

        // 미래 월(리포트 확정 시점 기준 먼 미래)은 거부하지 않고 빈 결과 200이다(RA-2)
        ReportMonthlyResponse future = reportService.getMonthlyReport(user.getId(), user.getId(), 2099, 12);
        assertThat(future.movieCount()).isZero();
        assertThat(future.watchCount()).isZero();
        assertThat(future.ratingDistribution()).hasSize(10);
        assertThat(future.watchTypeDistribution()).isEmpty();
    }

    private void assertCalendar(User user) {
        ReportCalendarResponse february = reportService.getCalendar(user.getId(), user.getId(), 2026, 2);

        assertThat(february.days()).hasSize(1);
        assertThat(february.days().get(0).date()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(february.days().get(0).records()).hasSize(1);
        assertThat(february.days().get(0).records().get(0).rating()).isNull();

        ReportCalendarResponse empty = reportService.getCalendar(user.getId(), user.getId(), 2026, 3);
        assertThat(empty.days()).isEmpty();
    }

    private void assertInvalidPeriodRejected(User user) {
        assertThatThrownBy(() -> reportService.getMonthlyReport(user.getId(), user.getId(), 1899, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_PERIOD));

        assertThatThrownBy(() -> reportService.getCalendar(user.getId(), user.getId(), 2026, 13))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_PERIOD));
    }
}
