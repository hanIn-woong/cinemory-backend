package com.project.cinemory.domain.boxoffice;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 박스오피스 수동 연결(admin-spec 17) — 코드 단위 일괄 연결 · 영화에 코드 심김 · 충돌 409 · 미연결 0개 404 ·
 * 해제가 행과 영화 코드를 함께 비움 · 감사 로그 · 인가.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminBoxOfficeLinkApiTest {

    private static final String CD = "ZT0001";
    private static final String OTHER_CD = "ZT0002";
    // 다른 테스트·시드 데이터보다 항상 위에 오도록 먼 미래 날짜를 쓴다(미연결 목록 정렬: 최근 순위 진입일 DESC)
    private static final LocalDate D1 = LocalDate.of(2031, 3, 14);
    private static final LocalDate D2 = LocalDate.of(2031, 3, 15);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BoxOfficeRecordRepository recordRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminActionLogRepository logRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;

    private String adminToken;
    private String userToken;
    private Movie movie;

    @BeforeEach
    void setUp() {
        User admin = userRepository.save(User.createLocal("bo-admin@test.com", "{noop}pw", "박스관리자"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        User user = userRepository.save(User.createLocal("bo-user@test.com", "{noop}pw", "일반"));
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        movie = movieRepository.save(Movie.builder().tmdbId(98_811L).title("zqt Box Movie").originalTitle("zqt Box").build());
        // 같은 코드의 날짜 2행(DAILY) + 다른 코드 1행
        record(D1, RankType.DAILY, CD, 7, null);
        record(D2, RankType.DAILY, CD, 3, null);
        record(D2, RankType.DAILY, OTHER_CD, 5, null);
        em.flush();
        em.clear();
    }

    private BoxOfficeRecord record(LocalDate date, RankType type, String cd, int rank, Movie linked) {
        return recordRepository.save(BoxOfficeRecord.builder().targetDate(date).rankType(type).boxOfficeRank(rank)
                .koficMovieCd(cd).movieTitleSnapshot("제목" + cd).openDate(LocalDate.of(2031, 3, 1)).movie(linked)
                .audienceCount(100).audienceAcc(1000L).salesAmount(1L).build());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private String body(Long movieId) {
        return "{\"movieId\":" + movieId + "}";
    }

    private List<AdminActionLog> logs(AdminAction action) {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == action).toList();
    }

    @Test
    void 미연결_목록은_코드로_묶고_최근_진입일_순이다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/box-office/unlinked")))
                .andExpect(status().isOk())
                // 두 코드의 lastDate가 같으면 코드순 — ZT0001이 먼저
                .andExpect(jsonPath("$.content[0].koficMovieCd").value(CD))
                .andExpect(jsonPath("$.content[0].recordCount").value(2))
                .andExpect(jsonPath("$.content[0].bestRank").value(3))
                .andExpect(jsonPath("$.content[0].firstDate").value("2031-03-14"))
                .andExpect(jsonPath("$.content[0].lastDate").value("2031-03-15"))
                .andExpect(jsonPath("$.content[1].koficMovieCd").value(OTHER_CD));
    }

    @Test
    void 연결하면_모든_날짜_행이_이어지고_영화에_코드가_심긴다() throws Exception {
        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", CD))
                        .contentType(MediaType.APPLICATION_JSON).content(body(movie.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedRecords").value(2))
                .andExpect(jsonPath("$.movieId").value(movie.getId()));

        em.flush();
        em.clear();
        assertThat(movieRepository.findById(movie.getId()).orElseThrow().getKoficMovieCd()).isEqualTo(CD);
        assertThat(recordRepository.countByKoficMovieCdAndMovieIsNull(CD)).isZero();
        assertThat(recordRepository.countByKoficMovieCdAndMovieIsNull(OTHER_CD)).isEqualTo(1);

        List<AdminActionLog> logs = logs(AdminAction.BOX_OFFICE_LINK);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetId()).isEqualTo(movie.getId());
    }

    @Test
    void 영화에_다른_코드가_있으면_409() throws Exception {
        movieRepository.findById(movie.getId()).orElseThrow().linkKoficCode("ZT9999");
        em.flush();

        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", CD))
                        .contentType(MediaType.APPLICATION_JSON).content(body(movie.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KOFIC_CODE_CONFLICT"));
        assertThat(logs(AdminAction.BOX_OFFICE_LINK)).isEmpty();
    }

    @Test
    void 코드가_이미_다른_영화에_붙어_있으면_409() throws Exception {
        Movie holder = movieRepository.save(Movie.builder().tmdbId(98_812L).title("zqt Holder").originalTitle("zqt Holder").build());
        holder.linkKoficCode(CD);
        em.flush();

        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", CD))
                        .contentType(MediaType.APPLICATION_JSON).content(body(movie.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KOFIC_CODE_CONFLICT"));
    }

    @Test
    void 미연결_행이_없으면_404_영화가_없으면_404() throws Exception {
        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", "NOPE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(movie.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOX_OFFICE_NOT_FOUND"));
        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", CD))
                        .contentType(MediaType.APPLICATION_JSON).content(body(-1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
    }

    @Test
    void movieId가_없으면_400() throws Exception {
        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", CD))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 해제하면_행과_영화_코드가_함께_비워지고_감사_로그가_남는다() throws Exception {
        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/link", CD))
                .contentType(MediaType.APPLICATION_JSON).content(body(movie.getId()))).andExpect(status().isOk());
        em.flush();
        em.clear();

        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/unlink", CD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlinkedRecords").value(2));

        em.flush();
        em.clear();
        assertThat(movieRepository.findById(movie.getId()).orElseThrow().getKoficMovieCd()).isNull();
        assertThat(recordRepository.countByKoficMovieCdAndMovieIsNull(CD)).isEqualTo(2);

        List<AdminActionLog> logs = logs(AdminAction.BOX_OFFICE_UNLINK);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetail()).containsEntry("previousMovieId", movie.getId().intValue());
    }

    @Test
    void 연결된_것이_전혀_없으면_해제는_404() throws Exception {
        mockMvc.perform(asAdmin(post("/api/admin/box-office/{cd}/unlink", CD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 일반_사용자와_비로그인은_거부된다() throws Exception {
        mockMvc.perform(get("/api/admin/box-office/unlinked")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/box-office/unlinked").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/box-office/{cd}/link", CD).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(movie.getId())))
                .andExpect(status().isForbidden());
    }
}
