package com.project.cinemory.domain.theater;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.theater.dto.TheaterSeedData;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.domain.theater.service.TheaterPersister;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 극장명 정리(admin-spec 19) - 덮어쓰기 PATCH(멱등·해제·길이·감사 로그)와 동기화 상호작용(덮어쓰기 유지·규칙 이름 갱신·
 * dryRun {@code renamed}·원본 이름 저장), "덮어쓴 극장" 필터, 인가.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTheaterNameApiTest {

    private static final String CODE = "9999|서울특별시 중구 테스트로 1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TheaterRepository theaterRepository;
    @Autowired
    private TheaterPersister persister;
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
    private Theater theater;

    @BeforeEach
    void setUp() {
        // 대량 숨김 판정 등이 다른 극장에 흔들리지 않게 비운다 - 테스트 트랜잭션이라 롤백된다
        theaterRepository.deleteAll();
        em.flush();
        User admin = userRepository.save(User.createLocal("tname-admin@test.com", "{noop}pw", "극장이름관리자"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        User user = userRepository.save(User.createLocal("tname-user@test.com", "{noop}pw", "일반"));
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        // 규칙 적용 전 상태: 원본 "CJ CGV 테스트" 그대로 표시되던 극장
        theater = theaterRepository.save(Theater.builder().sourceCode(CODE).name("CJ CGV 테스트").sourceName("CJ CGV 테스트")
                .chainName("CGV").address("서울 중구").latitude(new BigDecimal("37.5000000")).longitude(new BigDecimal("127.0000000"))
                .screenCount(3).build());
        em.flush();
        em.clear();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private String body(String name) {
        return name == null ? "{\"name\":null}" : "{\"name\":\"" + name + "\"}";
    }

    private Theater reload() {
        em.flush();
        em.clear();
        return theaterRepository.findById(theater.getId()).orElseThrow();
    }

    private List<AdminActionLog> logs() {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == AdminAction.THEATER_NAME_UPDATE).toList();
    }

    private TheaterSeedData row(String displayName, String sourceName) {
        return new TheaterSeedData(CODE, displayName, "CGV", "서울 중구", new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000"), 3, null, sourceName);
    }

    // ---- PATCH ----

    @Test
    void 덮어쓰면_표시_이름이_바뀌고_응답에_원본과_덮어쓰기가_담긴다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", theater.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("CGV 강남 테스트")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CGV 강남 테스트"))
                .andExpect(jsonPath("$.nameOverride").value("CGV 강남 테스트"))
                .andExpect(jsonPath("$.sourceName").value("CJ CGV 테스트"));

        Theater reloaded = reload();
        assertThat(reloaded.getName()).isEqualTo("CGV 강남 테스트");
        assertThat(reloaded.getNameOverride()).isEqualTo("CGV 강남 테스트");

        List<AdminActionLog> logs = logs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetail()).containsEntry("after", "CGV 강남 테스트").containsEntry("sourceName", "CJ CGV 테스트");
        assertThat(logs.get(0).getDetail()).containsKey("before");   // null까지 명시 - 처음 입력과 해제를 구별
    }

    @Test
    void 앞뒤_공백은_뗀다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", theater.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("  CGV 강남  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameOverride").value("CGV 강남"));
    }

    @Test
    void 해제하면_원본에_규칙을_다시_적용한_이름으로_돌아온다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", theater.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(body("무엇이든"))).andExpect(status().isOk());

        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", theater.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CGV 테스트"))   // "CJ CGV 테스트" → 규칙 이름
                .andExpect(jsonPath("$.nameOverride").doesNotExist());

        assertThat(reload().getNameOverride()).isNull();
        assertThat(logs()).hasSize(2);
    }

    @Test
    void 같은_값이면_기록_없이_200이고_빈_값을_덮어쓴_적_없는_극장에_보내도_기록이_없다() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", theater.getId()))
                            .contentType(MediaType.APPLICATION_JSON).content(body("CGV 강남")))
                    .andExpect(status().isOk());
        }
        assertThat(logs()).hasSize(1);

        em.clear();
        Theater other = theaterRepository.save(Theater.builder().sourceCode("9999|다른").name("X").chainName("기타").address("a")
                .latitude(new BigDecimal("37.5000000")).longitude(new BigDecimal("127.0000000")).build());
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", other.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("   ")))
                .andExpect(status().isOk());
        assertThat(logs()).hasSize(1);
    }

    @Test
    void 백자를_넘으면_400이고_바뀌지_않는다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", theater.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("가".repeat(101))))
                .andExpect(status().isBadRequest());

        assertThat(reload().getNameOverride()).isNull();
        assertThat(logs()).isEmpty();
    }

    @Test
    void 없는_극장은_404() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/{id}/name", -1L))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isNotFound());
    }

    // ---- 동기화 상호작용 ----

    @Test
    void 덮어쓰기는_동기화_뒤에도_유지되고_원본_이름만_갱신된다() {
        theaterRepository.findById(theater.getId()).orElseThrow().changeNameOverride("내가 정한 이름", "무시됨");
        em.flush();
        em.clear();

        TheaterPersister.SyncResult result = persister.reconcile(
                List.of(row("CGV 테스트", "씨지브이 테스트")), Set.of(CODE), false, false);

        Theater reloaded = reload();
        assertThat(reloaded.getName()).isEqualTo("내가 정한 이름");
        assertThat(reloaded.getNameOverride()).isEqualTo("내가 정한 이름");
        assertThat(reloaded.getSourceName()).isEqualTo("씨지브이 테스트");
        assertThat(result.renamed()).isZero();   // 덮어쓴 극장은 이름이 바뀌지 않으므로 세지 않는다
    }

    @Test
    void 덮어쓰기가_없으면_동기화가_규칙_이름으로_갱신하고_renamed로_센다() {
        TheaterPersister.SyncResult result = persister.reconcile(
                List.of(row("CGV 테스트", "CJ CGV 테스트")), Set.of(CODE), false, false);

        assertThat(reload().getName()).isEqualTo("CGV 테스트");
        assertThat(result.renamed()).isEqualTo(1);
        assertThat(result.renamedExamples()).singleElement().satisfies(r -> {
            assertThat(r.before()).isEqualTo("CJ CGV 테스트");
            assertThat(r.after()).isEqualTo("CGV 테스트");
        });
    }

    @Test
    void dryRun은_renamed를_보여주되_저장하지_않는다() {
        TheaterPersister.SyncResult result = persister.reconcile(
                List.of(row("CGV 테스트", "CJ CGV 테스트")), Set.of(CODE), false, true);

        assertThat(result.renamed()).isEqualTo(1);
        assertThat(reload().getName()).isEqualTo("CJ CGV 테스트");
    }

    @Test
    void renamedExamples는_20건까지만_담는다() {
        for (int i = 0; i < 25; i++) {
            theaterRepository.save(Theater.builder().sourceCode("9999|bulk" + i).name("CJ CGV " + i).chainName("CGV").address("a")
                    .latitude(new BigDecimal("37.5000000")).longitude(new BigDecimal("127.0000000")).build());
        }
        em.flush();
        List<TheaterSeedData> rows = new java.util.ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < 25; i++) {
            rows.add(new TheaterSeedData("9999|bulk" + i, "CGV " + i, "CGV", "a", new BigDecimal("37.5000000"),
                    new BigDecimal("127.0000000"), 1, null, "CJ CGV " + i));
            keys.add("9999|bulk" + i);
        }

        TheaterPersister.SyncResult result = persister.reconcile(rows, keys, true, true);

        assertThat(result.renamed()).isEqualTo(25);
        assertThat(result.renamedExamples()).hasSize(20);
    }

    @Test
    void 신규_극장은_원본_이름을_저장한다() {
        theaterRepository.deleteAll();
        em.flush();

        persister.reconcile(List.of(row("CGV 새극장", "씨지브이 새극장")), Set.of(CODE), false, false);

        Theater created = theaterRepository.findBySourceCodeIn(List.of(CODE)).get(0);
        assertThat(created.getName()).isEqualTo("CGV 새극장");
        assertThat(created.getSourceName()).isEqualTo("씨지브이 새극장");
    }

    // ---- 필터 · 인가 ----

    @Test
    void 덮어쓴_극장_필터() throws Exception {
        theaterRepository.findById(theater.getId()).orElseThrow().changeNameOverride("덮음", "x");
        theaterRepository.save(Theater.builder().sourceCode("9999|plain").name("일반극장").chainName("기타").address("a")
                .latitude(new BigDecimal("37.5000000")).longitude(new BigDecimal("127.0000000")).build());
        em.flush();
        em.clear();

        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("nameOverridden", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].nameOverride").value("덮음"));
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("nameOverridden", "false")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("일반극장"));
        mockMvc.perform(asAdmin(get("/api/admin/theaters")))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void 검색은_원본_이름도_본다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("query", "CJ CGV")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 일반_사용자와_비로그인은_거부된다() throws Exception {
        mockMvc.perform(patch("/api/admin/theaters/{id}/name", theater.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/theaters/{id}/name", theater.getId()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isForbidden());
    }
}
