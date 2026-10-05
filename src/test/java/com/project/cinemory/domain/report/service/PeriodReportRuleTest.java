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
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.report.dto.RatingBucketResponse;
import com.project.cinemory.domain.report.dto.ReportMonthlyResponse;
import com.project.cinemory.domain.report.dto.ReportYearlyResponse;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기간 리포트(월간·연간) 규칙 검증(4-8-H). 시나리오 1~4는 스펙이 명시한 <b>완료 판정 테스트</b>이고,
 * 월간도 같은 규칙이므로 1~3을 월 단위로 한 번씩 더 돌린다. 별점은 1~10 저장값 — 10이 5점이다.
 */
@SpringBootTest
@Transactional
class PeriodReportRuleTest {

    private static final BigDecimal FIVE_STARS = BigDecimal.valueOf(10.0);
    private static final BigDecimal FOUR_STARS = BigDecimal.valueOf(8.0);
    private static final BigDecimal THREE_STARS = BigDecimal.valueOf(6.0);

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
    private WatchRecordService watchRecordService;

    private User createUser(String email) {
        User user = User.createLocal(email, "{noop}password", email.substring(0, email.indexOf('@')));
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        return userRepository.save(user);
    }

    private Movie createMovie(long tmdbId, String title) {
        return movieRepository.save(Movie.builder()
                .tmdbId(tmdbId).title(title).runtime(100).releaseDate(LocalDate.of(2010, 1, 1))
                .posterPath("/" + tmdbId + ".jpg")
                .build());
    }

    private void record(User user, Movie movie, LocalDate watchDate, BigDecimal rating) {
        watchRecordService.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(movie.getId(), watchDate, WatchType.THEATER, null, null, rating, null));
    }

    private long bucket(List<RatingBucketResponse> distribution, int rating) {
        return distribution.stream().filter(b -> b.rating() == rating).findFirst().orElseThrow().count();
    }

    /** MySQL DAYOFWEEK() 기준(1=일 ~ 7=토). */
    private int dayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7 + 1;
    }

    // ── 완료 판정 1 : 해를 넘긴 재관람이 과거 기간을 바꾸지 않는다 ───────────────────

    @Test
    void 해를_넘긴_재관람으로_대표가_옮겨가도_과거_연간_리포트는_불변이다() {
        User user = createUser("period-1@test.com");
        Movie movie = createMovie(97_001L, "기간 영화 1");

        record(user, movie, LocalDate.of(2014, 3, 10), FIVE_STARS);
        ReportYearlyResponse before = reportService.getYearlyReport(user.getId(), user.getId(), 2014);
        ReportMonthlyResponse beforeMonth = reportService.getMonthlyReport(user.getId(), user.getId(), 2014, 3);

        // 2026 재관람 4점 — 이 회차가 대표가 된다
        record(user, movie, LocalDate.of(2026, 1, 10), FOUR_STARS);
        ReportYearlyResponse after = reportService.getYearlyReport(user.getId(), user.getId(), 2014);
        ReportMonthlyResponse afterMonth = reportService.getMonthlyReport(user.getId(), user.getId(), 2014, 3);

        assertThat(after).isEqualTo(before);
        assertThat(afterMonth).isEqualTo(beforeMonth);
        assertThat(after.fiveStarMovies()).extracting(f -> f.title()).containsExactly("기간 영화 1");
        assertThat(bucket(after.ratingDistribution(), 10)).isEqualTo(1);
        assertThat(after.averageRating()).isEqualByComparingTo("10.0");
        assertThat(afterMonth.averageRating()).isEqualByComparingTo("10.0");

        // 2026 쪽은 2026 회차만 본다
        ReportYearlyResponse y2026 = reportService.getYearlyReport(user.getId(), user.getId(), 2026);
        assertThat(y2026.fiveStarMovies()).isEmpty();
        assertThat(y2026.averageRating()).isEqualByComparingTo("8.0");
    }

    // ── 완료 판정 2 : 같은 기간 5점 → 3점 ─────────────────────────────────────

    @Test
    void 같은_해_5점_후_3점이면_5점작에는_남고_분포와_평균은_3점이다() {
        User user = createUser("period-2@test.com");
        Movie movie = createMovie(97_002L, "기간 영화 2");

        record(user, movie, LocalDate.of(2020, 3, 5), FIVE_STARS);
        record(user, movie, LocalDate.of(2020, 11, 5), THREE_STARS);

        ReportYearlyResponse yearly = reportService.getYearlyReport(user.getId(), user.getId(), 2020);
        assertThat(yearly.fiveStarMovies()).hasSize(1);
        assertThat(yearly.fiveStarMovies().get(0).fiveStarDate()).isEqualTo(LocalDate.of(2020, 3, 5));
        assertThat(bucket(yearly.ratingDistribution(), 6)).isEqualTo(1);
        assertThat(bucket(yearly.ratingDistribution(), 10)).isZero(); // 5점작 수(1) ≠ 10점 막대(0) — 의도된 차이
        assertThat(yearly.averageRating()).isEqualByComparingTo("6.0");
        assertThat(yearly.movieCount()).isEqualTo(1);
        assertThat(yearly.watchCount()).isEqualTo(2);
    }

    @Test
    void 같은_달_5점_후_3점이면_월간_분포와_평균은_3점이다() {
        User user = createUser("period-2m@test.com");
        Movie movie = createMovie(97_003L, "기간 영화 2m");

        record(user, movie, LocalDate.of(2021, 3, 5), FIVE_STARS);
        record(user, movie, LocalDate.of(2021, 3, 20), THREE_STARS);

        ReportMonthlyResponse monthly = reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 3);
        assertThat(bucket(monthly.ratingDistribution(), 6)).isEqualTo(1);
        assertThat(bucket(monthly.ratingDistribution(), 10)).isZero();
        assertThat(monthly.averageRating()).isEqualByComparingTo("6.0");
    }

    // ── 완료 판정 3 : 별점 없는 재관람이 그 기간의 평가를 지우지 않는다 ──────────────

    @Test
    void 같은_해_별점_없는_재관람은_5점_평가를_지우지_않는다() {
        User user = createUser("period-3@test.com");
        Movie movie = createMovie(97_004L, "기간 영화 3");

        record(user, movie, LocalDate.of(2020, 3, 5), FIVE_STARS);
        record(user, movie, LocalDate.of(2020, 11, 5), null);

        ReportYearlyResponse yearly = reportService.getYearlyReport(user.getId(), user.getId(), 2020);
        assertThat(bucket(yearly.ratingDistribution(), 10)).isEqualTo(1);
        assertThat(yearly.averageRating()).isEqualByComparingTo("10.0");
        assertThat(yearly.fiveStarMovies()).hasSize(1);
    }

    @Test
    void 같은_달_별점_없는_재관람은_월간_5점_평가를_지우지_않는다() {
        User user = createUser("period-3m@test.com");
        Movie movie = createMovie(97_005L, "기간 영화 3m");

        record(user, movie, LocalDate.of(2021, 3, 5), FIVE_STARS);
        record(user, movie, LocalDate.of(2021, 3, 20), null);

        ReportMonthlyResponse monthly = reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 3);
        assertThat(bucket(monthly.ratingDistribution(), 10)).isEqualTo(1);
        assertThat(monthly.averageRating()).isEqualByComparingTo("10.0");
    }

    // ── 완료 판정 4 : 별점 없는 기록만 있는 감독도 "많이 본 감독"에 나온다 ─────────────

    @Test
    void 별점_없는_기록만_있는_감독도_많이_본_감독에_나온다() {
        User user = createUser("period-4@test.com");
        Person director = personRepository.save(Person.of(97_101L, "별점없는감독", null));
        Movie first = createMovie(97_006L, "기간 영화 4a");
        Movie second = createMovie(97_007L, "기간 영화 4b");
        movieDirectorRepository.save(MovieDirector.of(first, director));
        movieDirectorRepository.save(MovieDirector.of(second, director));

        record(user, first, LocalDate.of(2022, 5, 1), null);
        record(user, second, LocalDate.of(2022, 5, 2), null);
        record(user, second, LocalDate.of(2022, 5, 3), null); // 재관람 — 편수는 그대로 2

        ReportYearlyResponse yearly = reportService.getYearlyReport(user.getId(), user.getId(), 2022);
        assertThat(yearly.mostWatchedDirector()).isNotNull();
        assertThat(yearly.mostWatchedDirector().name()).isEqualTo("별점없는감독");
        assertThat(yearly.mostWatchedDirector().count()).isEqualTo(2L);
        assertThat(yearly.mostWatchedDirector().score()).isNull();
        assertThat(yearly.averageRating()).isNull();

        ReportMonthlyResponse monthly = reportService.getMonthlyReport(user.getId(), user.getId(), 2022, 5);
        assertThat(monthly.mostWatchedDirector()).isNotNull();
        assertThat(monthly.mostWatchedDirector().count()).isEqualTo(2L);
    }

    // ── 연간 전용 지표 ────────────────────────────────────────────────────

    @Test
    void 연간_전용_지표를_집계한다() {
        User user = createUser("period-yearly@test.com");

        Genre drama = genreRepository.save(Genre.of(97_201, "기간드라마"));
        Genre thriller = genreRepository.save(Genre.of(97_202, "기간스릴러"));
        Country korea = countryRepository.save(Country.of("Q8", "기간코리아"));
        Person lead = personRepository.save(Person.of(97_102L, "주연배우", null));
        Person minor = personRepository.save(Person.of(97_103L, "단역배우", null));

        Movie a = createMovie(97_008L, "연간 A");
        Movie b = createMovie(97_009L, "연간 B");
        Movie c = createMovie(97_010L, "연간 C");
        // 장르 — 드라마 3편, 스릴러 1편. weight는 쓰지 않으므로 일부러 다르게 둔다
        movieGenreRepository.save(MovieGenre.of(a, drama, new BigDecimal("0.5")));
        movieGenreRepository.save(MovieGenre.of(a, thriller, new BigDecimal("0.5")));
        movieGenreRepository.save(MovieGenre.of(b, drama, BigDecimal.ONE));
        movieGenreRepository.save(MovieGenre.of(c, drama, BigDecimal.ONE));
        movieCountryRepository.save(MovieCountry.of(a, korea, BigDecimal.ONE));
        // 주연은 2편, 단역은 3편 — 단역은 세지 않으므로 주연이 1위
        movieActorRepository.save(MovieActor.builder()
                .movie(a).person(lead).characterName("주인공").displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieActorRepository.save(MovieActor.builder()
                .movie(b).person(lead).characterName("주인공").displayOrder(0).roleTier(RoleTier.LEAD).build());
        for (Movie movie : List.of(a, b, c)) {
            movieActorRepository.save(MovieActor.builder()
                    .movie(movie).person(minor).characterName("행인").displayOrder(15).roleTier(RoleTier.MINOR).build());
        }

        LocalDate aDate = LocalDate.of(2023, 2, 14);
        LocalDate cDate = LocalDate.of(2023, 1, 7);
        record(user, a, aDate, FIVE_STARS);
        record(user, a, LocalDate.of(2023, 8, 1), FIVE_STARS); // 같은 해 두 번째 5점 — fiveStarDate는 처음 날
        record(user, b, LocalDate.of(2023, 2, 20), THREE_STARS);
        record(user, c, cDate, FIVE_STARS);
        record(user, c, LocalDate.of(2024, 1, 1), FIVE_STARS); // 다른 해 — 2023에 섞이지 않는다

        ReportYearlyResponse yearly = reportService.getYearlyReport(null, user.getId(), 2023);

        assertThat(yearly.year()).isEqualTo(2023);
        assertThat(yearly.movieCount()).isEqualTo(3);
        assertThat(yearly.watchCount()).isEqualTo(4);
        assertThat(yearly.totalWatchedMinutes()).isEqualTo(400);
        assertThat(yearly.averageRating()).isEqualByComparingTo("8.7"); // (10 + 6 + 10) / 3

        // 5점작 — 처음 5점 준 날짜 오름차순, 영화당 한 번
        assertThat(yearly.fiveStarMovies()).extracting(f -> f.title()).containsExactly("연간 C", "연간 A");
        assertThat(yearly.fiveStarMovies().get(0).fiveStarDate()).isEqualTo(cDate);
        assertThat(yearly.fiveStarMovies().get(1).fiveStarDate()).isEqualTo(aDate);
        assertThat(yearly.fiveStarMovies().get(1).posterPath()).isEqualTo("/97008.jpg");

        // 장르·국가 — 편수
        assertThat(yearly.mostWatchedGenres()).extracting(g -> g.name()).containsExactly("기간드라마", "기간스릴러");
        assertThat(yearly.mostWatchedGenres().get(0).count()).isEqualTo(3L);
        assertThat(yearly.mostWatchedGenres().get(0).score()).isNull();
        assertThat(yearly.mostWatchedCountries()).hasSize(1);
        assertThat(yearly.mostWatchedCountries().get(0).count()).isEqualTo(1L);

        // 배우 — LEAD·SUPPORTING만, 재관람은 한 편
        assertThat(yearly.mostWatchedActor()).isNotNull();
        assertThat(yearly.mostWatchedActor().name()).isEqualTo("주연배우");
        assertThat(yearly.mostWatchedActor().count()).isEqualTo(2L);

        // 월별 추이 12개 고정, 공백 달 0
        assertThat(yearly.monthlyTrend()).hasSize(12);
        assertThat(yearly.monthlyTrend()).allMatch(m -> m.year() == 2023);
        assertThat(yearly.monthlyTrend()).extracting(m -> m.month())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(yearly.monthlyTrend().get(0).watchCount()).isEqualTo(1);  // 1월 C
        assertThat(yearly.monthlyTrend().get(1).watchCount()).isEqualTo(2);  // 2월 A, B
        assertThat(yearly.monthlyTrend().get(2).watchCount()).isZero();
        assertThat(yearly.monthlyTrend().get(7).watchCount()).isEqualTo(1);  // 8월 A 재관람

        // 요일 분포 7개 고정, 합 = 그해 회차 수
        assertThat(yearly.weekdayDistribution()).hasSize(7);
        assertThat(yearly.weekdayDistribution()).extracting(w -> w.weekday()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(yearly.weekdayDistribution().stream().mapToLong(w -> w.count()).sum()).isEqualTo(4);
        assertThat(yearly.weekdayDistribution().get(dayOfWeek(aDate) - 1).count()).isPositive();

        assertThat(yearly.watchTypeDistribution()).hasSize(1);
        assertThat(yearly.watchTypeDistribution().get(0).watchType()).isEqualTo("THEATER");
    }

    @Test
    void 미래_연도는_빈_결과이고_범위_밖_연도는_거부한다() {
        User user = createUser("period-empty@test.com");

        ReportYearlyResponse future = reportService.getYearlyReport(user.getId(), user.getId(), 2099);
        assertThat(future.movieCount()).isZero();
        assertThat(future.averageRating()).isNull();
        assertThat(future.ratingDistribution()).hasSize(10);
        assertThat(future.monthlyTrend()).hasSize(12);
        assertThat(future.weekdayDistribution()).hasSize(7);
        assertThat(future.mostWatchedDirector()).isNull();
        assertThat(future.mostWatchedActor()).isNull();
        assertThat(future.mostWatchedGenres()).isEmpty();
        assertThat(future.fiveStarMovies()).isEmpty();

        for (int invalidYear : new int[]{1899, 2101}) {
            assertThatThrownBy(() -> reportService.getYearlyReport(user.getId(), user.getId(), invalidYear))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_REPORT_PERIOD));
        }
    }
}
