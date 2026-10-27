package com.project.cinemory.domain.theater;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.theater.dto.BookingSource;
import com.project.cinemory.domain.theater.dto.TheaterResponse;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.domain.theater.service.TheaterQueryService;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 극장 예매 링크·숨김(cinemap-spec C-5-1, C-8) — 링크 판정 3가지, 주변 극장에서 숨긴 극장 제외,
 * PATCH 검증(400 INVALID_BOOKING_URL)·감사 로그·멱등, 관리자 목록 필터, 인가.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TheaterBookingApiTest {

    // 서울시청 근처 — 테스트 극장은 전부 이 점에서 수백 m 안
    private static final double LAT = 37.5663;
    private static final double LNG = 126.9779;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TheaterRepository theaterRepository;
    @Autowired
    private TheaterQueryService queryService;
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

    @BeforeEach
    void setUp() {
        theaterRepository.deleteAll();
        em.flush();
        User admin = userRepository.save(User.createLocal("theater-admin@test.com", "{noop}pw", "극장관리자"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        User user = userRepository.save(User.createLocal("theater-user@test.com", "{noop}pw", "일반"));
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
    }

    private Theater save(String code, String name, String chain) {
        return theaterRepository.save(Theater.builder().sourceCode(code).name(name).chainName(chain).address("서울 " + name)
                .latitude(new BigDecimal("37.5670000")).longitude(new BigDecimal("126.9780000")).screenCount(3).build());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private List<AdminActionLog> bookingLogs() {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == AdminAction.THEATER_BOOKING_URL_UPDATE).toList();
    }

    private TheaterResponse nearby(String name) {
        em.flush();
        return queryService.getNearbyTheaters(LAT, LNG, 5000, 50).stream()
                .filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    // ── 예매 링크 판정 (C-8-1)

    @Test
    void 극장_링크가_있으면_THEATER_없으면_체인_기본_URL_둘_다_없으면_null() {
        Theater own = save("t-own", "링크있는CGV", "CGV");
        own.updateBookingUrl("https://example.com/own");
        save("t-chain", "링크없는CGV", "CGV");
        save("t-none", "독립영화관", "기타");

        TheaterResponse withOwn = nearby("링크있는CGV");
        assertThat(withOwn.bookingSource()).isEqualTo(BookingSource.THEATER);
        assertThat(withOwn.bookingUrl()).isEqualTo("https://example.com/own");   // 체인 기본보다 우선

        TheaterResponse chainOnly = nearby("링크없는CGV");
        assertThat(chainOnly.bookingSource()).isEqualTo(BookingSource.CHAIN);
        assertThat(chainOnly.bookingUrl()).startsWith("https://");               // application.yml의 chain-booking-urls.CGV

        TheaterResponse none = nearby("독립영화관");
        assertThat(none.bookingSource()).isNull();
        assertThat(none.bookingUrl()).isNull();
    }

    @Test
    void 주변_극장에서_숨긴_극장은_빠지고_복구하면_다시_나온다() {
        Theater open = save("t-open", "영업중", "CGV");
        Theater closed = save("t-closed", "숨김", "CGV");
        closed.close(Instant.now());

        assertThat(nearby("영업중")).isNotNull();
        assertThat(nearby("숨김")).isNull();

        closed.reopen();
        assertThat(nearby("숨김")).isNotNull();
        assertThat(open.isClosed()).isFalse();
    }

    // ── PATCH /api/admin/theaters/{id}/booking-url (C-8-2)

    private String patchUrl(Theater theater) {
        return "/api/admin/theaters/" + theater.getId() + "/booking-url";
    }

    @Test
    void PATCH는_저장하고_이전_값_새_값을_감사_로그에_남긴다() throws Exception {
        Theater theater = save("t-1", "대상", "기타");
        em.flush();

        mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingUrl\":\"https://example.com/a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theaterId").value(theater.getId()))
                .andExpect(jsonPath("$.bookingUrl").value("https://example.com/a"));
        mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingUrl\":\"https://example.com/b\"}"))
                .andExpect(status().isOk());

        List<AdminActionLog> logs = bookingLogs();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(l -> {
            assertThat(l.getTargetType()).isEqualTo(AdminTargetType.THEATER);
            assertThat(l.getTargetId()).isEqualTo(theater.getId());
        });
        AdminActionLog second = logs.stream().filter(l -> "https://example.com/b".equals(l.getDetail().get("after")))
                .findFirst().orElseThrow();
        assertThat(second.getDetail()).containsEntry("before", "https://example.com/a");
    }

    @Test
    void null은_삭제이고_같은_값은_기록_없이_200이다() throws Exception {
        Theater theater = save("t-2", "삭제대상", "기타");
        theater.updateBookingUrl("https://example.com/a");
        em.flush();

        // 같은 값 → 기록 없음
        mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingUrl\":\"https://example.com/a\"}"))
                .andExpect(status().isOk());
        assertThat(bookingLogs()).isEmpty();

        // null → 삭제 1건
        mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingUrl\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingUrl").doesNotExist());
        List<AdminActionLog> logs = bookingLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetail()).containsEntry("before", "https://example.com/a");
        assertThat(logs.get(0).getDetail().get("after")).isNull();

        // 이미 없는 상태에서 null → 변화 없음
        mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingUrl\":null}"))
                .andExpect(status().isOk());
        assertThat(bookingLogs()).hasSize(1);
    }

    @Test
    void 형식_위반은_400_INVALID_BOOKING_URL이고_기록이_없다() throws Exception {
        Theater theater = save("t-3", "검증대상", "기타");
        em.flush();

        List<String> invalid = List.of(
                "http://example.com/a",                              // https 아님
                "ftp://example.com",
                "example.com/a",                                     // 스킴 없음
                "https://example.com/a b",                           // 중간 공백
                " https://example.com/a",                            // 앞 공백
                "https://example.com/a ",                            // 뒤 공백
                "",                                                  // 빈 문자열은 삭제가 아니라 위반
                "https://",                                          // 호스트 없음
                "https://example.com/" + "a".repeat(500));           // 500자 초과
        for (String url : invalid) {
            mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bookingUrl\":\"" + url + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_BOOKING_URL"));
        }

        // 정확히 500자는 통과한다
        String max = "https://example.com/" + "a".repeat(500 - "https://example.com/".length());
        assertThat(max).hasSize(500);
        mockMvc.perform(asAdmin(patch(patchUrl(theater))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingUrl\":\"" + max + "\"}"))
                .andExpect(status().isOk());
        assertThat(bookingLogs()).hasSize(1);   // 통과한 500자 하나뿐
    }

    @Test
    void 없는_극장은_404이고_일반_사용자는_403이다() throws Exception {
        mockMvc.perform(asAdmin(patch("/api/admin/theaters/" + Long.MAX_VALUE + "/booking-url"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingUrl\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THEATER_NOT_FOUND"));

        Theater theater = save("t-4", "권한", "기타");
        em.flush();
        mockMvc.perform(patch(patchUrl(theater)).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingUrl\":null}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/theaters").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/admin/theaters (C-8-2)

    @Test
    void 관리자_목록은_검색과_세_필터를_지원하고_숨긴_극장도_보인다() throws Exception {
        Theater withUrl = save("t-a", "알파시네마", "기타");
        withUrl.updateBookingUrl("https://example.com/a");
        save("t-b", "베타CGV", "CGV");
        Theater closed = save("t-c", "감마폐업", "기타");
        closed.close(Instant.now());
        em.flush();

        mockMvc.perform(asAdmin(get("/api/admin/theaters")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));                       // 숨긴 극장 포함
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("query", "알파")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("알파시네마"));
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("query", "서울 베타")))   // 주소로도 검색
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("chain", "CGV")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("hasBookingUrl", "true")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].bookingUrl").value("https://example.com/a"));
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("hasBookingUrl", "false")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("closed", "true")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("감마폐업"))
                .andExpect(jsonPath("$.content[0].closedAt").exists());
        mockMvc.perform(asAdmin(get("/api/admin/theaters").param("closed", "false")))
                .andExpect(jsonPath("$.totalElements").value(2));
    }
}
