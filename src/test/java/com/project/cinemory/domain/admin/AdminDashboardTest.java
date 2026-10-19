package com.project.cinemory.domain.admin;

import com.project.cinemory.domain.admin.dto.AdminDashboardResponse;
import com.project.cinemory.domain.admin.dto.AdminDashboardResponse.DailyPoint;
import com.project.cinemory.domain.admin.service.AdminDashboardService;
import com.project.cinemory.domain.auth.entity.RefreshToken;
import com.project.cinemory.domain.auth.repository.RefreshTokenRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 대시보드(admin-spec 13절, 테스트 13-3). 실 데이터와 겹치지 않게 "오늘"을 먼 미래(2031-03-15)로 고정해 그 구간에 직접 행을 넣는다.
 * 행의 {@code created_at}은 네이티브 UPDATE로 원하는 시각에 둔다(자정 경계 검증).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminDashboardTest {

    private static final LocalDate TODAY = LocalDate.of(2031, 3, 15);

    @Autowired
    private AdminDashboardService service;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;

    private int seq;

    @BeforeEach
    void setUp() {
        seq = 0;
    }

    private User newUser(LocalDateTime createdAt) {
        User user = userRepository.save(User.createLocal("dash-" + (++seq) + "-" + UUID.randomUUID() + "@test.com", "{noop}pw", "대시" + seq));
        setCreatedAt("user", user.getId(), createdAt);
        return user;
    }

    private Movie newMovie(String title, LocalDateTime createdAt) {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(990_000L + (++seq)).title(title).build());
        setCreatedAt("movie", movie.getId(), createdAt);
        return movie;
    }

    private void setCreatedAt(String table, Long id, LocalDateTime at) {
        em.flush();
        em.createNativeQuery("update " + table + " set created_at = :at where id = :id")
                .setParameter("at", at).setParameter("id", id).executeUpdate();
    }

    private void token(User user, LocalDateTime at) {
        RefreshToken token = refreshTokenRepository.save(RefreshToken.issue(user, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""), at.plusDays(14)));
        setCreatedAt("refresh_token", token.getId(), at);
    }

    /** 한 사용자의 시청 기록 n건을 모두 {@code at}에 만든 것으로 만든다(재관람 포함). */
    private void records(User user, Movie movie, int n, LocalDateTime at) {
        for (int i = 0; i < n; i++) {
            watchRecordService.addWatchRecord(user.getId(),
                    new WatchRecordCreateRequest(movie.getId(), null, null, null, null, null, null));
        }
        em.flush();
        em.createNativeQuery("update watch_record set created_at = :at where user_id = :uid and movie_id = :mid")
                .setParameter("at", at).setParameter("uid", user.getId()).setParameter("mid", movie.getId()).executeUpdate();
    }

    private DailyPoint day(AdminDashboardResponse r, LocalDate date) {
        return r.daily().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
    }

    @Test
    void 빈_날은_0으로_채우고_오래된_날부터_오늘까지_days일이다() {
        AdminDashboardResponse r = service.getDashboard(7, TODAY);

        assertThat(r.daily()).hasSize(7);
        assertThat(r.daily().get(0).date()).isEqualTo(LocalDate.of(2031, 3, 9));
        assertThat(r.daily().get(6).date()).isEqualTo(TODAY);
        assertThat(r.daily()).allSatisfy(d -> {
            assertThat(d.newUsers()).isZero();
            assertThat(d.activeUsers()).isZero();
            assertThat(d.newWatchRecords()).isZero();
            assertThat(d.newReviews()).isZero();
            assertThat(d.newMovies()).isZero();
        });
        assertThat(r.topMovies()).isEmpty();
        assertThat(r.recent().newUsers()).isZero();
    }

    @Test
    void days_경계는_7과_90이고_밖은_400이다() throws Exception {
        assertThat(service.getDashboard(7, TODAY).daily()).hasSize(7);
        assertThat(service.getDashboard(90, TODAY).daily()).hasSize(90);
        assertThat(service.getDashboard(90, TODAY).daily().get(0).date()).isEqualTo(TODAY.minusDays(89));

        for (int bad : new int[]{6, 91, 0, -5}) {
            assertThatThrownBy(() -> service.getDashboard(bad, TODAY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }

        User admin = newUser(TODAY.minusYears(1).atStartOfDay());
        String token = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        mockMvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(30));   // 기본 30일
        mockMvc.perform(get("/api/admin/dashboard").param("days", "6").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard").param("days", "91").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard").param("days", "abc").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 대시보드는_비로그인_401_일반_사용자_403이다() throws Exception {
        User user = newUser(TODAY.minusYears(1).atStartOfDay());
        String userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);

        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 날짜는_자정_경계로_나뉘고_구간_밖은_세지_않는다() {
        newUser(LocalDateTime.of(2031, 3, 14, 23, 59, 59));     // 14일
        newUser(LocalDateTime.of(2031, 3, 15, 0, 0, 0));        // 15일(오늘 0시 정각)
        newUser(LocalDateTime.of(2031, 3, 15, 23, 59, 59));     // 15일(오늘 끝)
        newUser(LocalDateTime.of(2031, 3, 16, 0, 0, 0));        // 내일 — 제외
        newUser(LocalDateTime.of(2031, 3, 8, 23, 59, 59));      // days=7 구간(3/9~) 직전 — 제외

        AdminDashboardResponse r = service.getDashboard(7, TODAY);

        assertThat(day(r, LocalDate.of(2031, 3, 14)).newUsers()).isEqualTo(1);
        assertThat(day(r, TODAY).newUsers()).isEqualTo(2);
        assertThat(r.daily().stream().mapToLong(DailyPoint::newUsers).sum()).isEqualTo(3);
        assertThat(r.recent().newUsers()).isEqualTo(3);
    }

    @Test
    void 활성_사용자는_날짜별_중복을_제거하고_7일_구간은_고유_사용자다() {
        User a = newUser(TODAY.minusYears(1).atStartOfDay());
        User b = newUser(TODAY.minusYears(1).atStartOfDay());
        // 같은 날 재발급 여러 번 = 1명
        token(a, LocalDateTime.of(2031, 3, 15, 9, 0));
        token(a, LocalDateTime.of(2031, 3, 15, 9, 30));
        token(a, LocalDateTime.of(2031, 3, 15, 10, 0));
        token(a, LocalDateTime.of(2031, 3, 14, 20, 0));
        token(b, LocalDateTime.of(2031, 3, 15, 11, 0));

        AdminDashboardResponse r = service.getDashboard(7, TODAY);

        assertThat(day(r, TODAY).activeUsers()).isEqualTo(2);
        assertThat(day(r, LocalDate.of(2031, 3, 14)).activeUsers()).isEqualTo(1);
        // 최근 7일: a는 이틀에 걸쳐 나와도 1명 — 일별 합(3)이 아니라 고유 사용자(2)
        assertThat(r.recent().activeUsers()).isEqualTo(2);
    }

    @Test
    void 최근_7일_지표는_days와_무관하게_고정이다() {
        User a = newUser(LocalDateTime.of(2031, 3, 15, 10, 0));
        newUser(LocalDateTime.of(2031, 2, 20, 10, 0));          // 7일 밖, 30일 안
        token(a, LocalDateTime.of(2031, 3, 15, 10, 0));
        token(a, LocalDateTime.of(2031, 2, 20, 10, 0));          // 7일 밖 — 최근 활성에서 제외

        AdminDashboardResponse d7 = service.getDashboard(7, TODAY);
        AdminDashboardResponse d30 = service.getDashboard(30, TODAY);
        AdminDashboardResponse d90 = service.getDashboard(90, TODAY);

        assertThat(d7.recent()).isEqualTo(d30.recent()).isEqualTo(d90.recent());
        assertThat(d30.recent().newUsers()).isEqualTo(1);
        assertThat(d30.recent().activeUsers()).isEqualTo(1);
        // 일별 시리즈에는 30일 안의 가입이 모두 있다
        assertThat(d30.daily().stream().mapToLong(DailyPoint::newUsers).sum()).isEqualTo(2);
        assertThat(d7.daily().stream().mapToLong(DailyPoint::newUsers).sum()).isEqualTo(1);
    }

    @Test
    void 기록_사용자와_신규_기록_리뷰_영화_등록이_날짜별로_센다() {
        User a = newUser(TODAY.minusYears(1).atStartOfDay());
        User b = newUser(TODAY.minusYears(1).atStartOfDay());
        User c = newUser(TODAY.minusYears(1).atStartOfDay());
        Movie movie = newMovie("집계 영화", LocalDateTime.of(2031, 3, 15, 12, 0));
        newMovie("지난 영화", LocalDateTime.of(2031, 3, 1, 12, 0));

        records(a, movie, 2, LocalDateTime.of(2031, 3, 15, 10, 0));
        records(b, movie, 1, LocalDateTime.of(2031, 3, 10, 10, 0));
        records(c, movie, 1, LocalDateTime.of(2031, 3, 8, 10, 0));    // 최근 7일 밖(30일 안)
        Review review = reviewRepository.save(Review.of(a, movie, "리뷰"));
        setCreatedAt("review", review.getId(), LocalDateTime.of(2031, 3, 15, 13, 0));

        AdminDashboardResponse r = service.getDashboard(30, TODAY);

        assertThat(day(r, TODAY).newWatchRecords()).isEqualTo(2);
        assertThat(day(r, LocalDate.of(2031, 3, 10)).newWatchRecords()).isEqualTo(1);
        assertThat(day(r, LocalDate.of(2031, 3, 8)).newWatchRecords()).isEqualTo(1);
        assertThat(day(r, TODAY).newReviews()).isEqualTo(1);
        assertThat(day(r, TODAY).newMovies()).isEqualTo(1);
        assertThat(day(r, LocalDate.of(2031, 3, 1)).newMovies()).isEqualTo(1);
        assertThat(r.recent().newWatchRecords()).isEqualTo(3);        // 3/9~3/15 — c(3/8)는 제외
        assertThat(r.recent().recordingUsers()).isEqualTo(2);         // a, b
    }

    @Test
    void TOP10은_기록_수_내림차순이고_동률은_movie_id_오름차순이며_표시_제목을_쓴다() {
        User user = newUser(TODAY.minusYears(1).atStartOfDay());
        LocalDateTime at = LocalDateTime.of(2031, 3, 15, 10, 0);
        Movie m1 = newMovie("Alpha", at);
        Movie m2 = newMovie("Beta", at);
        Movie m3 = newMovie("Gamma", at);
        List<Movie> ones = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ones.add(newMovie("Single " + i, at));
        }
        m1.changeTitleKo("알파");   // 표시 제목(한글 제목 우선)

        records(user, m1, 3, at);
        records(user, m2, 3, at);   // m1과 동률 → movie_id 오름차순으로 m1, m2
        records(user, m3, 2, at);
        for (Movie one : ones) {
            records(user, one, 1, at);   // 1건 동률 9편 → 앞의 7편만 TOP10에 든다
        }

        var top = service.getDashboard(30, TODAY).topMovies();

        assertThat(top).hasSize(10);
        assertThat(top.get(0).movieId()).isEqualTo(m1.getId());
        assertThat(top.get(0).recordCount()).isEqualTo(3);
        assertThat(top.get(0).title()).isEqualTo("알파");
        assertThat(top.get(1).movieId()).isEqualTo(m2.getId());
        assertThat(top.get(2).movieId()).isEqualTo(m3.getId());
        assertThat(top.get(2).recordCount()).isEqualTo(2);
        assertThat(top.subList(3, 10)).extracting(t -> t.movieId())
                .containsExactlyElementsOf(ones.subList(0, 7).stream().map(Movie::getId).toList());
    }

    @Test
    void 누적_totals는_동기화_이력_없는_영화와_한글_이름_없는_인물을_센다() {
        AdminDashboardResponse before = service.getDashboard(7, TODAY);

        Movie synced = newMovie("동기화됨", LocalDateTime.of(2031, 3, 15, 1, 0));
        synced.markSynced(LocalDateTime.of(2031, 3, 15, 2, 0));
        newMovie("동기화 안 됨", LocalDateTime.of(2031, 3, 15, 1, 0));
        personRepository.save(Person.of(990_901L, "dash Foreign None", null));                        // 한글 없음 → 센다
        Person withKo = personRepository.save(Person.of(990_902L, "dash Foreign Ko", null));
        withKo.changeNameKo("대시 포린");                                                              // 한글명 있음 → 제외
        personRepository.save(Person.of(990_903L, "대시 홍길동", null));                                // 원본이 한글 → 제외
        newUser(LocalDateTime.of(2031, 3, 15, 1, 0));
        em.flush();

        AdminDashboardResponse after = service.getDashboard(7, TODAY);

        assertThat(after.totals().movies() - before.totals().movies()).isEqualTo(2);
        assertThat(after.totals().moviesNeverSynced() - before.totals().moviesNeverSynced()).isEqualTo(1);
        assertThat(after.totals().personsMissingKorean() - before.totals().personsMissingKorean()).isEqualTo(1);
        assertThat(after.totals().users() - before.totals().users()).isEqualTo(1);
    }
}
