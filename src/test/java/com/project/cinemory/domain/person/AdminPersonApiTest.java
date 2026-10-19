package com.project.cinemory.domain.person;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

/**
 * 관리자 인물 API(admin-spec 12-3, 테스트 12-5) — 목록·필터·건수, PATCH 정규화·멱등·감사 로그, 출연진 조회, 인가.
 * 이름은 고유 토큰({@code zqk})으로 실 데이터와 분리한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class AdminPersonApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private MovieActorRepository movieActorRepository;
    @Autowired
    private MovieDirectorRepository movieDirectorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminActionLogRepository logRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;
    @Autowired
    private EntityManagerFactory emf;

    private User admin;
    private String adminToken;
    private Person foreignOnly;    // 한글 없음 + 한글명 없음  → missingKorean에 걸린다
    private Person foreignWithKo;  // 한글명 있음              → 제외
    private Person hangulOriginal; // 원본 자체가 한글          → 제외

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.createLocal("person-admin@test.com", "{noop}pw", "인물관리자"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        foreignOnly = personRepository.save(Person.of(98_601L, "zqk Foreign One", null));
        foreignWithKo = personRepository.save(Person.of(98_602L, "zqk Foreign Two", "/two.jpg"));
        foreignWithKo.changeNameKo("제크 포린 투");
        hangulOriginal = personRepository.save(Person.of(98_603L, "zqk 홍길동", null));
        em.flush();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private List<AdminActionLog> nameKoLogs() {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == AdminAction.PERSON_NAME_KO_UPDATE).toList();
    }

    @Test
    void q는_원본명과_한글명_양쪽에_걸리고_정렬은_id_오름차순이다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/persons").param("q", "zqk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].personId").value(foreignOnly.getId()))
                .andExpect(jsonPath("$.content[1].personId").value(foreignWithKo.getId()))
                .andExpect(jsonPath("$.content[2].personId").value(hangulOriginal.getId()));

        // 한글명으로만 걸리는 검색어
        mockMvc.perform(asAdmin(get("/api/admin/persons").param("q", "포린 투")))
                .andExpect(jsonPath("$.content[0].personId").value(foreignWithKo.getId()))
                .andExpect(jsonPath("$.content[0].nameKo").value("제크 포린 투"))
                .andExpect(jsonPath("$.content[0].name").value("zqk Foreign Two"));
        // 와일드카드는 리터럴
        mockMvc.perform(asAdmin(get("/api/admin/persons").param("q", "zqk%"))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void missingKorean은_한글_이름이_하나도_없는_인물만_거른다() throws Exception {
        // 3종: 한글 없음(포함) / 한글명 보유(제외) / 원본이 한글(제외)
        mockMvc.perform(asAdmin(get("/api/admin/persons").param("q", "zqk").param("missingKorean", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].personId").value(foreignOnly.getId()));
    }

    @Test
    void creditCount는_출연과_연출을_합한다() throws Exception {
        Movie m1 = movieRepository.save(Movie.builder().tmdbId(98_611L).title("크레딧 1").build());
        Movie m2 = movieRepository.save(Movie.builder().tmdbId(98_612L).title("크레딧 2").build());
        movieActorRepository.save(MovieActor.builder().movie(m1).person(foreignOnly).characterName("a")
                .displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieActorRepository.save(MovieActor.builder().movie(m2).person(foreignOnly).characterName("b")
                .displayOrder(1).roleTier(RoleTier.LEAD).build());
        movieDirectorRepository.save(MovieDirector.of(m1, foreignOnly));
        em.flush();

        mockMvc.perform(asAdmin(get("/api/admin/persons").param("q", "zqk")))
                .andExpect(jsonPath("$.content[0].creditCount").value(3))
                .andExpect(jsonPath("$.content[1].creditCount").value(0));
    }

    @Test
    void 목록_한_페이지는_쿼리_4회다_목록_count_출연_연출() throws Exception {
        // 첫 페이지 content가 size보다 작으면 count가 생략되므로 size를 일치 건수(3)보다 작게
        em.flush();
        em.clear();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(asAdmin(get("/api/admin/persons").param("q", "zqk").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        // 인가가 요청 사용자를 읽는 쿼리는 이 요청에 없다(JWT 필터는 토큰만 본다) — 관리자 감사 로그도 이 목록은 남기지 않는다
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
    }

    @Test
    void PATCH는_공백을_자르고_저장하며_감사_로그_한_줄을_남긴다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignOnly.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameKo\":\"  제크 포린 원  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameKo").value("제크 포린 원"))
                .andExpect(jsonPath("$.name").value("zqk Foreign One"));

        List<AdminActionLog> logs = nameKoLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetType()).isEqualTo(AdminTargetType.PERSON);
        assertThat(logs.get(0).getTargetId()).isEqualTo(foreignOnly.getId());
        assertThat(logs.get(0).getAdminUserId()).isEqualTo(admin.getId());
        assertThat(logs.get(0).getDetail()).containsEntry("after", "제크 포린 원").containsKey("before");
        assertThat(logs.get(0).getDetail().get("before")).isNull();   // 처음 입력 — before가 null임을 로그가 안다
    }

    @Test
    void 같은_값을_다시_보내면_저장도_기록도_없이_200이다() throws Exception {
        String body = "{\"nameKo\":\"제크 포린 투\"}";   // foreignWithKo는 이미 이 값

        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignWithKo.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameKo").value("제크 포린 투"));
        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignWithKo.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":\" 제크 포린 투 \"}"))   // 공백만 다른 같은 값
                .andExpect(status().isOk());

        assertThat(nameKoLogs()).isEmpty();
    }

    @Test
    void null과_빈_문자열은_해제이고_해제도_before_after를_남긴다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignWithKo.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameKo").doesNotExist());

        List<AdminActionLog> logs = nameKoLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetail()).containsEntry("before", "제크 포린 투");
        assertThat(logs.get(0).getDetail().get("after")).isNull();

        // 이미 해제된 상태에서 null을 보내면 변화 없음 → 기록 없음
        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignWithKo.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":null}"))
                .andExpect(status().isOk());
        assertThat(nameKoLogs()).hasSize(1);
    }

    @Test
    void 없는_인물은_404이고_백한_자_초과는_400이며_둘_다_기록이_없다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + Long.MAX_VALUE + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":\"가\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));

        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignOnly.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":\"" + "가".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());

        // 100자는 통과한다
        mockMvc.perform(asAdmin(patch("/api/admin/persons/" + foreignOnly.getId() + "/name-ko"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":\"" + "가".repeat(100) + "\"}"))
                .andExpect(status().isOk());

        assertThat(nameKoLogs()).hasSize(1);   // 성공한 100자 하나뿐
    }

    @Test
    void 출연진_조회는_원본명과_한글명을_따로_싣고_없는_영화는_404다() throws Exception {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(98_621L).title("출연진 조회").build());
        movieActorRepository.save(MovieActor.builder().movie(movie).person(foreignWithKo).characterName("역할")
                .displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieDirectorRepository.save(MovieDirector.of(movie, foreignOnly));
        em.flush();

        mockMvc.perform(asAdmin(get("/api/admin/movies/" + movie.getId() + "/credits")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cast[0].personId").value(foreignWithKo.getId()))
                .andExpect(jsonPath("$.cast[0].tmdbPersonId").value(98_602))
                .andExpect(jsonPath("$.cast[0].name").value("zqk Foreign Two"))
                .andExpect(jsonPath("$.cast[0].nameKo").value("제크 포린 투"))
                .andExpect(jsonPath("$.cast[0].roleTier").value("LEAD"))
                .andExpect(jsonPath("$.directors[0].personId").value(foreignOnly.getId()))
                .andExpect(jsonPath("$.directors[0].nameKo").doesNotExist());

        mockMvc.perform(asAdmin(get("/api/admin/movies/" + Long.MAX_VALUE + "/credits"))).andExpect(status().isNotFound());
    }

    @Test
    void 인물_API는_비로그인_401_일반_사용자_403이다() throws Exception {
        String userToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.USER);

        mockMvc.perform(get("/api/admin/persons")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/persons").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/persons/" + foreignOnly.getId() + "/name-ko")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameKo\":\"가\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/movies/1/credits").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
