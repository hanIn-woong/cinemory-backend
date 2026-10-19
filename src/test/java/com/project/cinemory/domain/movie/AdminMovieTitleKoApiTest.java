package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 영화 한글 제목 API(admin-spec 12-B-3, 테스트 12-B-5) — PATCH 정규화·멱등·해제·검증·감사 로그, 관리자 검색, 인가. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminMovieTitleKoApiTest {

    @Autowired
    private MockMvc mockMvc;
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

    private User admin;
    private String adminToken;
    private Movie movie;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.createLocal("title-admin@test.com", "{noop}pw", "제목관리자"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        movie = movieRepository.save(Movie.builder().tmdbId(98_801L).title("zqt Foreign Title").originalTitle("zqt Original").build());
        em.flush();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private String url() {
        return "/api/admin/movies/" + movie.getId() + "/title-ko";
    }

    private List<AdminActionLog> titleLogs() {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == AdminAction.MOVIE_TITLE_KO_UPDATE).toList();
    }

    @Test
    void PATCH는_공백을_자르고_상세를_돌려주며_감사_로그_한_줄을_남긴다() throws Exception {
        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleKo\":\"  제크 외국 제목  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(movie.getId()))
                .andExpect(jsonPath("$.title").value("zqt Foreign Title"))      // TMDB 제목은 그대로
                .andExpect(jsonPath("$.titleKo").value("제크 외국 제목"));

        List<AdminActionLog> logs = titleLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetType()).isEqualTo(AdminTargetType.MOVIE);
        assertThat(logs.get(0).getTargetId()).isEqualTo(movie.getId());
        assertThat(logs.get(0).getAdminUserId()).isEqualTo(admin.getId());
        assertThat(logs.get(0).getDetail()).containsEntry("after", "제크 외국 제목").containsKey("before");
        assertThat(logs.get(0).getDetail().get("before")).isNull();   // 처음 입력
    }

    @Test
    void 같은_값을_다시_보내면_저장도_기록도_없이_200이다() throws Exception {
        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"제크 외국 제목\"}"))
                .andExpect(status().isOk());
        em.flush();
        long afterFirst = logRepository.count();

        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\" 제크 외국 제목 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleKo").value("제크 외국 제목"));

        em.flush();
        assertThat(logRepository.count()).isEqualTo(afterFirst);   // 공백만 다른 같은 값 — 기록 없음
    }

    @Test
    void 빈_문자열과_null은_해제이고_해제도_before_after를_남긴다() throws Exception {
        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"제크 외국 제목\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleKo").doesNotExist());

        List<AdminActionLog> logs = titleLogs();
        assertThat(logs).hasSize(2);   // 입력 1 + 해제 1
        AdminActionLog clear = logs.stream().filter(l -> l.getDetail().get("after") == null).findFirst().orElseThrow();
        assertThat(clear.getDetail()).containsEntry("before", "제크 외국 제목");

        // 이미 해제된 상태에서 null → 변화 없음, 기록 없음
        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":null}"))
                .andExpect(status().isOk());
        assertThat(titleLogs()).hasSize(2);
    }

    @Test
    void 없는_영화는_404이고_이백오십육자_초과는_400이며_둘_다_기록이_없다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/movies/" + Long.MAX_VALUE + "/title-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"가\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));

        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleKo\":\"" + "가".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleKo\":\"" + "가".repeat(255) + "\"}"))
                .andExpect(status().isOk());

        assertThat(titleLogs()).hasSize(1);   // 성공한 255자 하나뿐
    }

    @Test
    void 관리자_영화_검색_q는_한글_제목에도_걸리고_목록에_원본과_한글_제목이_따로_나온다() throws Exception {
        mockMvc.perform(asAdmin(patch(url())).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"제크한글검색\"}"))
                .andExpect(status().isOk());
        em.flush();

        mockMvc.perform(asAdmin(get("/api/admin/movies").param("q", "제크한글검색")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].movieId").value(movie.getId()))
                .andExpect(jsonPath("$.content[0].title").value("zqt Foreign Title"))
                .andExpect(jsonPath("$.content[0].titleKo").value("제크한글검색"));
        // 한글 제목을 넣기 전의 검색어(원본)로도 그대로 걸린다
        mockMvc.perform(asAdmin(get("/api/admin/movies").param("q", "zqt Foreign")))
                .andExpect(jsonPath("$.content[0].movieId").value(movie.getId()));
    }

    @Test
    void 영화_한글_제목_API는_비로그인_401_일반_사용자_403이다() throws Exception {
        String userToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.USER);

        mockMvc.perform(patch(url()).contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"가\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(url()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleKo\":\"가\"}"))
                .andExpect(status().isForbidden());
    }
}
