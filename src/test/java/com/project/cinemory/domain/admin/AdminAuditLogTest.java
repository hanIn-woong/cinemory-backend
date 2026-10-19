package com.project.cinemory.domain.admin;

import com.project.cinemory.domain.admin.dto.AdminAuditLogResponse;
import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.admin.service.AdminAuditQueryService;
import com.project.cinemory.domain.boxoffice.service.BoxOfficeSyncService;
import com.project.cinemory.domain.country.service.CountrySeedService;
import com.project.cinemory.domain.genre.service.GenreSeedService;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.domain.movie.service.ResyncResult;
import com.project.cinemory.domain.movie.service.SeedResult;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 감사 로그(admin-spec 11절, 테스트 11-6). 시드·재동기화·박스오피스 서비스는 대역으로 바꿔 "기록 경로"만 검증한다 —
 * 그 서비스들의 동작은 각자의 테스트가 본다. MockMvc가 테스트 트랜잭션과 같은 스레드라 기록된 행이 바로 보이고 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAuditLogTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private AdminActionLogRepository logRepository;
    @Autowired
    private AdminAuditQueryService queryService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private MovieSeedService movieSeedService;
    @MockitoBean
    private GenreSeedService genreSeedService;
    @MockitoBean
    private CountrySeedService countrySeedService;
    @MockitoBean
    private BoxOfficeSyncService boxOfficeSyncService;

    private User admin;
    private User target;
    private String adminToken;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.createLocal("audit-admin@test.com", "{noop}pw", "감사관리자"));
        target = userRepository.save(User.createLocal("audit-target@test.com", "{noop}pw", "감사대상"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken)
                .with(r -> {
                    r.setRemoteAddr("203.0.113.9");
                    return r;
                });
    }

    private List<AdminActionLog> logs(AdminAction action) {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == action).toList();
    }

    private long totalLogs() {
        em.flush();
        return logRepository.count();
    }

    @Test
    void 회원_목록_조회는_q_sort_page를_남기고_공백_q는_뺀다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/users").param("q", "  audit-target ").param("sort", "NICKNAME_ASC")
                .param("page", "0"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(get("/api/admin/users").param("q", "   "))).andExpect(status().isOk());

        List<AdminActionLog> rows = logs(AdminAction.USER_LIST_VIEW);
        assertThat(rows).hasSize(2);
        AdminActionLog withQ = rows.stream().filter(l -> l.getDetail().containsKey("q")).findFirst().orElseThrow();
        assertThat(withQ.getDetail()).containsEntry("q", "audit-target").containsEntry("sort", "NICKNAME_ASC")
                .containsEntry("page", 0);
        AdminActionLog withoutQ = rows.stream().filter(l -> !l.getDetail().containsKey("q")).findFirst().orElseThrow();
        assertThat(withoutQ.getDetail()).containsEntry("sort", "CREATED_AT_DESC");
        assertThat(withQ.getTargetType()).isNull();
    }

    @Test
    void 회원_상세_조회는_대상_이메일_스냅샷_IP를_남긴다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/users/" + target.getId()))).andExpect(status().isOk());

        AdminActionLog row = logs(AdminAction.USER_DETAIL_VIEW).get(0);
        assertThat(row.getTargetType()).isEqualTo(AdminTargetType.USER);
        assertThat(row.getTargetId()).isEqualTo(target.getId());
        assertThat(row.getAdminUserId()).isEqualTo(admin.getId());
        assertThat(row.getAdminEmailSnapshot()).isEqualTo("audit-admin@test.com");
        assertThat(row.getIp()).isEqualTo("203.0.113.9");
        assertThat(row.getDetail()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void 없는_회원_조회는_로그를_남기지_않는다() throws Exception {
        long before = totalLogs();

        mockMvc.perform(asAdmin(get("/api/admin/users/" + Long.MAX_VALUE))).andExpect(status().isNotFound());

        assertThat(totalLogs()).isEqualTo(before);
    }

    @Test
    void 감사_로그_조회와_me는_기록되지_않는다() throws Exception {
        long before = totalLogs();

        mockMvc.perform(asAdmin(get("/api/admin/audit-logs"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(get("/api/admin/me"))).andExpect(status().isOk());

        assertThat(totalLogs()).isEqualTo(before);
    }

    @Test
    void 영화_단건_재동기화_성공은_1행_tmdbId를_남긴다() throws Exception {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(98_401L).title("감사 테스트").build());

        mockMvc.perform(asAdmin(post("/api/admin/movies/" + movie.getId() + "/resync"))).andExpect(status().isOk());

        List<AdminActionLog> rows = logs(AdminAction.MOVIE_RESYNC);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTargetType()).isEqualTo(AdminTargetType.MOVIE);
        assertThat(rows.get(0).getTargetId()).isEqualTo(movie.getId());
        assertThat(((Number) rows.get(0).getDetail().get("tmdbId")).longValue()).isEqualTo(98_401L);
    }

    @Test
    void 재동기화가_409_404_429로_실패하면_로그는_0행이다() throws Exception {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(98_402L).title("감사 테스트 2").build());
        long before = totalLogs();

        doThrow(new BusinessException(ErrorCode.SEED_ALREADY_RUNNING)).when(movieSeedService).resyncOne(movie.getId());
        mockMvc.perform(asAdmin(post("/api/admin/movies/" + movie.getId() + "/resync"))).andExpect(status().isConflict());

        doThrow(new BusinessException(ErrorCode.TMDB_MOVIE_NOT_FOUND)).when(movieSeedService).resyncOne(movie.getId());
        mockMvc.perform(asAdmin(post("/api/admin/movies/" + movie.getId() + "/resync"))).andExpect(status().isNotFound());

        doThrow(new BusinessException(ErrorCode.TMDB_RATE_LIMITED)).when(movieSeedService).resyncOne(movie.getId());
        mockMvc.perform(asAdmin(post("/api/admin/movies/" + movie.getId() + "/resync")))
                .andExpect(status().isTooManyRequests());

        doThrow(new BusinessException(ErrorCode.MOVIE_NOT_FOUND)).when(movieSeedService).resyncOne(anyLong());
        mockMvc.perform(asAdmin(post("/api/admin/movies/" + Long.MAX_VALUE + "/resync"))).andExpect(status().isNotFound());

        assertThat(totalLogs()).isEqualTo(before);
    }

    @Test
    void 일괄_작업_성공은_요청_파라미터와_결과_수를_남긴다() throws Exception {
        when(movieSeedService.resync(any(), any())).thenReturn(new ResyncResult(5, 1, true, 77L));
        when(movieSeedService.seedFromBoxOffice(any())).thenReturn(new SeedResult(3, 2, 1, false));
        when(movieSeedService.seedFromDiscover(any(), any(), any(), any(), any(), any())).thenReturn(new SeedResult(4, 0, 6, true));
        when(genreSeedService.seedAll()).thenReturn(19);
        when(countrySeedService.seedAll()).thenReturn(250);
        when(boxOfficeSyncService.syncDaily(any())).thenReturn(10);
        when(boxOfficeSyncService.rematchUnlinked(any())).thenReturn(7);

        mockMvc.perform(asAdmin(post("/api/admin/movies/resync").param("fromId", "10").param("limit", "50"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/api/admin/movies/seed/box-office").param("limit", "20"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/api/admin/movies/seed/discover").param("pages", "3").param("lang", "ko")
                .param("minVotes", "30"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/api/admin/genres/seed"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/api/admin/countries/seed"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/api/admin/box-office/sync").param("targetDate", "2026-10-01"))).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post("/api/admin/box-office/rematch").param("limit", "5"))).andExpect(status().isOk());

        AdminActionLog batch = logs(AdminAction.MOVIE_RESYNC_BATCH).get(0);
        assertThat(batch.getTargetType()).isNull();
        assertThat(batch.getDetail()).containsEntry("fromId", 10).containsEntry("limit", 50).containsEntry("updated", 5)
                .containsEntry("skipped", 1).containsEntry("stoppedByRateLimit", true);

        assertThat(logs(AdminAction.MOVIE_SEED_BOX_OFFICE).get(0).getDetail())
                .containsEntry("limit", 20).containsEntry("matched", 3).containsEntry("alreadyExists", 1);
        AdminActionLog discover = logs(AdminAction.MOVIE_SEED_DISCOVER).get(0);
        assertThat(discover.getDetail()).containsEntry("pages", 3).containsEntry("lang", "ko").containsEntry("minVotes", 30)
                .containsEntry("matched", 4).containsEntry("stoppedByRateLimit", true).doesNotContainKey("year");
        assertThat(logs(AdminAction.GENRE_SEED).get(0).getDetail()).containsEntry("inserted", 19);
        assertThat(logs(AdminAction.COUNTRY_SEED).get(0).getDetail()).containsEntry("inserted", 250);
        assertThat(logs(AdminAction.BOX_OFFICE_SYNC).get(0).getDetail())
                .containsEntry("targetDate", "2026-10-01").containsEntry("saved", 10);
        assertThat(logs(AdminAction.BOX_OFFICE_REMATCH).get(0).getDetail())
                .containsEntry("limit", 5).containsEntry("matched", 7);
    }

    @Test
    void 일괄_작업이_실패하면_로그는_0행이다() throws Exception {
        long before = totalLogs();
        when(movieSeedService.seedFromBoxOffice(any())).thenThrow(new BusinessException(ErrorCode.SEED_ALREADY_RUNNING));

        mockMvc.perform(asAdmin(post("/api/admin/movies/seed/box-office"))).andExpect(status().isConflict());

        assertThat(totalLogs()).isEqualTo(before);
    }

    // ---- 조회 API ----

    private AdminActionLog save(AdminAction action, AdminTargetType type, Long targetId, Long adminId) {
        AdminActionLog log = logRepository.save(AdminActionLog.builder().adminUserId(adminId)
                .adminEmailSnapshot("who@test.com").action(action).targetType(type).targetId(targetId).ip("1.1.1.1").build());
        em.flush();
        return log;
    }

    private void setCreatedAt(AdminActionLog log, LocalDateTime at) {
        em.createNativeQuery("update admin_action_log set created_at = :at where id = :id")
                .setParameter("at", at).setParameter("id", log.getId()).executeUpdate();
    }

    private List<Long> ids(PageResponse<AdminAuditLogResponse> page) {
        return page.content().stream().map(AdminAuditLogResponse::id).toList();
    }

    @Test
    void 필터는_AND로_조합되고_정렬은_최신순_고정이다() {
        AdminActionLog a = save(AdminAction.USER_DETAIL_VIEW, AdminTargetType.USER, 9_001L, 9_901L);
        AdminActionLog b = save(AdminAction.MOVIE_RESYNC, AdminTargetType.MOVIE, 9_002L, 9_901L);
        AdminActionLog c = save(AdminAction.MOVIE_RESYNC, AdminTargetType.MOVIE, 9_002L, 9_902L);
        setCreatedAt(a, LocalDateTime.of(2026, 3, 1, 10, 0));
        setCreatedAt(b, LocalDateTime.of(2026, 3, 2, 10, 0));
        setCreatedAt(c, LocalDateTime.of(2026, 3, 2, 10, 0));   // b와 같은 시각 → id 내림차순이 보조키
        em.clear();

        assertThat(ids(queryService.getLogs(null, null, null, 9_901L, null, null, 0, 20))).containsExactly(b.getId(), a.getId());
        assertThat(ids(queryService.getLogs(AdminAction.MOVIE_RESYNC, null, null, null, null, null, 0, 20)))
                .containsExactly(c.getId(), b.getId());
        assertThat(ids(queryService.getLogs(AdminAction.MOVIE_RESYNC, AdminTargetType.MOVIE, 9_002L, 9_901L, null, null, 0, 20)))
                .containsExactly(b.getId());
        assertThat(queryService.getLogs(AdminAction.USER_DETAIL_VIEW, AdminTargetType.MOVIE, null, null, null, null, 0, 20)
                .content()).isEmpty();
    }

    @Test
    void from_to는_날짜_경계를_포함한다() {
        AdminActionLog lastMoment = save(AdminAction.MOVIE_RESYNC, AdminTargetType.MOVIE, 9_010L, 9_903L);
        AdminActionLog midnight = save(AdminAction.MOVIE_RESYNC, AdminTargetType.MOVIE, 9_010L, 9_903L);
        setCreatedAt(lastMoment, LocalDateTime.of(2026, 3, 10, 23, 59, 59, 999_999_000));
        setCreatedAt(midnight, LocalDateTime.of(2026, 3, 11, 0, 0, 0));
        em.clear();

        LocalDate d10 = LocalDate.of(2026, 3, 10);
        LocalDate d11 = LocalDate.of(2026, 3, 11);
        // to는 그날 끝까지 포함, 다음 날 0시는 제외
        assertThat(ids(queryService.getLogs(null, null, 9_010L, null, null, d10, 0, 20))).containsExactly(lastMoment.getId());
        // from은 그날 0시부터 포함
        assertThat(ids(queryService.getLogs(null, null, 9_010L, null, d11, null, 0, 20))).containsExactly(midnight.getId());
        assertThat(ids(queryService.getLogs(null, null, 9_010L, null, d10, d11, 0, 20)))
                .containsExactly(midnight.getId(), lastMoment.getId());
    }

    @Test
    void detail_JSON은_저장_후_다시_읽어도_값과_타입이_유지된다() {
        AdminActionLog log = logRepository.save(AdminActionLog.builder().adminUserId(admin.getId())
                .adminEmailSnapshot("a@test.com").action(AdminAction.MOVIE_RESYNC_BATCH)
                .detail(java.util.Map.of("limit", 50, "stoppedByRateLimit", false, "lang", "ko")).build());
        em.flush();
        em.clear();

        AdminActionLog reloaded = logRepository.findById(log.getId()).orElseThrow();
        assertThat(reloaded.getDetail()).containsEntry("limit", 50).containsEntry("stoppedByRateLimit", false)
                .containsEntry("lang", "ko");
    }

    @Test
    void 스냅샷_이메일은_로그를_남긴_시점의_값이다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/users/" + target.getId()))).andExpect(status().isOk());

        PageResponse<AdminAuditLogResponse> page = queryService.getLogs(AdminAction.USER_DETAIL_VIEW, null, null,
                admin.getId(), null, null, 0, 20);
        assertThat(page.content().get(0).adminEmail()).isEqualTo("audit-admin@test.com");
    }

    @Test
    void 감사_로그_조회_API는_인가와_필터_형식을_검증한다() throws Exception {
        String userToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.USER);

        mockMvc.perform(get("/api/admin/audit-logs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(asAdmin(get("/api/admin/audit-logs").param("action", "NOT_AN_ACTION")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asAdmin(get("/api/admin/audit-logs").param("from", "2026-13-40")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asAdmin(get("/api/admin/audit-logs").param("adminUserId", String.valueOf(admin.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }
}
