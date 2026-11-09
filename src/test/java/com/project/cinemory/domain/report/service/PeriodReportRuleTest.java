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
        assertThat(yearly.mostWatchedDirector().profilePath()).isNull(); // 사진 없는 인물은 null
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
        Person lead = personRepository.save(Person.of(97_102L, "주연배우", "/lead.jpg"));
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
        assertThat(yearly.mostWatchedActor().profilePath()).isEqualTo("/lead.jpg");

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

    // ── 4-8-I-2 : 가장 많이 본 감독·배우는 2편 이상일 때만 ────────────────────────

    @Test
    void 감독이_각각_1편이면_많이_본_감독은_없고_한_감독이_2편이면_그_감독이_나온다() {
        User user = createUser("period-min-dir@test.com");
        Person a = personRepository.save(Person.of(97_301L, "최소감독A", null));
        Person b = personRepository.save(Person.of(97_302L, "최소감독B", null));
        Movie m1 = createMovie(97_301L, "최소 감독 1");
        Movie m2 = createMovie(97_302L, "최소 감독 2");
        Movie m3 = createMovie(97_303L, "최소 감독 3");
        movieDirectorRepository.save(MovieDirector.of(m1, a));
        movieDirectorRepository.save(MovieDirector.of(m2, b));
        movieDirectorRepository.save(MovieDirector.of(m3, a));

        record(user, m1, LocalDate.of(2021, 3, 1), null);
        record(user, m2, LocalDate.of(2021, 3, 2), null);
        // 3월: A 1편·B 1편 → 1위가 없다. 연간도 같은 규칙 - 2021년은 아직 A 1편 B 1편
        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 3).mostWatchedDirector()).isNull();
        assertThat(reportService.getYearlyReport(user.getId(), user.getId(), 2021).mostWatchedDirector()).isNull();

        record(user, m3, LocalDate.of(2021, 3, 3), null);
        // A가 2편(m1, m3)
        var monthly = reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 3).mostWatchedDirector();
        assertThat(monthly).isNotNull();
        assertThat(monthly.name()).isEqualTo("최소감독A");
        assertThat(monthly.count()).isEqualTo(2L);
        var yearly = reportService.getYearlyReport(user.getId(), user.getId(), 2021).mostWatchedDirector();
        assertThat(yearly).isNotNull();
        assertThat(yearly.name()).isEqualTo("최소감독A");
    }

    @Test
    void 같은_영화를_두_번_봐도_한_편이라_많이_본_감독이_되지_않는다() {
        User user = createUser("period-min-rewatch@test.com");
        Person a = personRepository.save(Person.of(97_303L, "재관람감독", null));
        Movie m1 = createMovie(97_304L, "최소 재관람");
        movieDirectorRepository.save(MovieDirector.of(m1, a));

        record(user, m1, LocalDate.of(2021, 4, 1), null);
        record(user, m1, LocalDate.of(2021, 4, 2), null); // 재관람 - COUNT(DISTINCT movie)라 여전히 1편

        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 4).mostWatchedDirector()).isNull();
        assertThat(reportService.getYearlyReport(user.getId(), user.getId(), 2021).mostWatchedDirector()).isNull();
    }

    @Test
    void 배우도_각각_1편이면_없고_한_배우가_2편이면_그_배우가_나온다() {
        User user = createUser("period-min-actor@test.com");
        Person a = personRepository.save(Person.of(97_305L, "최소배우A", null));
        a.changeNameKo("최소배우 한글명"); // 연간 최다 배우도 name_ko를 우선한다(COALESCE, admin-spec 12-2)
        Person b = personRepository.save(Person.of(97_306L, "최소배우B", null));
        Movie m1 = createMovie(97_305L, "최소 배우 1");
        Movie m2 = createMovie(97_306L, "최소 배우 2");
        Movie m3 = createMovie(97_307L, "최소 배우 3");
        movieActorRepository.save(MovieActor.builder().movie(m1).person(a).characterName("갑").displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieActorRepository.save(MovieActor.builder().movie(m2).person(b).characterName("을").displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieActorRepository.save(MovieActor.builder().movie(m3).person(a).characterName("갑").displayOrder(0).roleTier(RoleTier.LEAD).build());

        record(user, m1, LocalDate.of(2021, 6, 1), null);
        record(user, m2, LocalDate.of(2021, 6, 2), null);
        assertThat(reportService.getYearlyReport(user.getId(), user.getId(), 2021).mostWatchedActor()).isNull();

        record(user, m3, LocalDate.of(2021, 6, 3), null);
        var actor = reportService.getYearlyReport(user.getId(), user.getId(), 2021).mostWatchedActor();
        assertThat(actor).isNotNull();
        assertThat(actor.name()).isEqualTo("최소배우 한글명");
        assertThat(actor.count()).isEqualTo(2L);
    }

    // ── 4-8-I-3 : 월간 "가장 많이 본 배우" ────────────────────────────────────────

    private void actor(Movie movie, Person person, RoleTier tier) {
        movieActorRepository.save(MovieActor.builder().movie(movie).person(person)
                .characterName("역").displayOrder(0).roleTier(tier).build());
    }

    @Test
    void 월간_많이_본_배우는_같은_달_2편_이상일_때만_나오고_다른_달_기록은_세지_않는다() {
        User user = createUser("period-month-actor@test.com");
        Person a = personRepository.save(Person.of(97_401L, "월간배우A", "/a.jpg"));
        Movie m1 = createMovie(97_401L, "월간 배우 1");
        Movie m2 = createMovie(97_402L, "월간 배우 2");
        Movie m3 = createMovie(97_403L, "월간 배우 3");
        actor(m1, a, RoleTier.LEAD);
        actor(m2, a, RoleTier.SUPPORTING);
        actor(m3, a, RoleTier.LEAD);

        record(user, m1, LocalDate.of(2021, 8, 1), null);
        record(user, m3, LocalDate.of(2021, 9, 1), null); // 다른 달
        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 8).mostWatchedActor()).isNull();

        record(user, m2, LocalDate.of(2021, 8, 2), null);
        var actor = reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 8).mostWatchedActor();
        assertThat(actor).isNotNull();
        assertThat(actor.name()).isEqualTo("월간배우A");
        assertThat(actor.count()).isEqualTo(2L); // 9월 기록은 세지 않는다
        assertThat(actor.profilePath()).isEqualTo("/a.jpg");
        assertThat(actor.score()).isNull();
    }

    @Test
    void 월간_같은_영화_재관람은_1편이라_많이_본_배우가_되지_않는다() {
        User user = createUser("period-month-actor-rewatch@test.com");
        Person a = personRepository.save(Person.of(97_402L, "월간재관람배우", null));
        Movie m1 = createMovie(97_404L, "월간 재관람 배우");
        actor(m1, a, RoleTier.LEAD);

        record(user, m1, LocalDate.of(2021, 10, 1), null);
        record(user, m1, LocalDate.of(2021, 10, 2), null);

        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 10).mostWatchedActor()).isNull();
    }

    @Test
    void 월간_애니메이션_2편에만_나온_성우는_많이_본_배우가_아니다() {
        User user = createUser("period-month-actor-anim@test.com");
        Genre animation = genreRepository.findByTmdbGenreIdIn(List.of(16)).stream().findFirst()
                .orElseGet(() -> genreRepository.save(Genre.of(16, "애니메이션")));
        Person voice = personRepository.save(Person.of(97_403L, "월간성우", null));
        Movie m1 = createMovie(97_405L, "월간 애니 1");
        Movie m2 = createMovie(97_406L, "월간 애니 2");
        movieGenreRepository.save(MovieGenre.of(m1, animation, BigDecimal.ONE));
        movieGenreRepository.save(MovieGenre.of(m2, animation, BigDecimal.ONE));
        actor(m1, voice, RoleTier.LEAD);
        actor(m2, voice, RoleTier.LEAD);

        record(user, m1, LocalDate.of(2021, 11, 1), null);
        record(user, m2, LocalDate.of(2021, 11, 2), null);

        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 11).mostWatchedActor()).isNull();
    }

    @Test
    void 월간_많이_본_요일은_같은_요일_2회_미만이면_null이다() {
        User user = createUser("period-min-weekday1@test.com");
        Movie m1 = createMovie(97_450L, "최소 요일 1");
        Movie m2 = createMovie(97_451L, "최소 요일 2");
        record(user, m1, LocalDate.of(2021, 12, 6), null); // 월
        record(user, m2, LocalDate.of(2021, 12, 7), null); // 화 - 요일마다 1회

        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 12).mostWatchedWeekday()).isNull();
    }

    @Test
    void 월간_많이_본_요일은_같은_요일_2회_이상이면_나온다() {
        User user = createUser("period-min-weekday2@test.com");
        Movie m1 = createMovie(97_452L, "최소 요일 3");
        Movie m2 = createMovie(97_453L, "최소 요일 4");
        Movie m3 = createMovie(97_454L, "최소 요일 5");
        record(user, m1, LocalDate.of(2021, 12, 6), null);  // 월
        record(user, m2, LocalDate.of(2021, 12, 13), null); // 월
        record(user, m3, LocalDate.of(2021, 12, 7), null);  // 화 1회

        assertThat(reportService.getMonthlyReport(user.getId(), user.getId(), 2021, 12).mostWatchedWeekday())
                .isEqualTo(dayOfWeek(LocalDate.of(2021, 12, 6)));
    }

    @Test
    void 장르_국가_분포는_1편이어도_그대로_나온다() {
        User user = createUser("period-min-genre@test.com");
        Genre genre = genreRepository.save(Genre.of(97_203, "최소드라마"));
        Movie m1 = createMovie(97_308L, "최소 장르 1");
        movieGenreRepository.save(MovieGenre.of(m1, genre, BigDecimal.ONE));
        record(user, m1, LocalDate.of(2021, 7, 1), null);

        var yearly = reportService.getYearlyReport(user.getId(), user.getId(), 2021);
        assertThat(yearly.mostWatchedGenres()).hasSize(1);
        assertThat(yearly.mostWatchedGenres().get(0).count()).isEqualTo(1L);
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
