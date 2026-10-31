package com.project.cinemory.domain.watch;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.ott.repository.OttPlatformRepository;
import com.project.cinemory.domain.report.dto.ReportStatisticsResponse;
import com.project.cinemory.domain.report.dto.WatchTypeCountResponse;
import com.project.cinemory.domain.report.service.ReportService;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordUpdateRequest;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 관람 방식 확장(TV·VOD·DISC)과 OTT 직접 입력 (service-layer-spec 4-3-B, 테스트 ①~⑥). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * 새 값이 실제 ENUM 컬럼에 들어가는지(V41)는 {@code flush}로 INSERT/UPDATE를 내보내 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WatchTypeExpandTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private OttPlatformRepository ottPlatformRepository;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User user() {
        User user = User.createOAuth("watchtype" + SEQ.incrementAndGet() + "@watchtype.test", "관람방식", null);
        em.persist(user);
        return user;
    }

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(400_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("관람 방식 영화").build();
        em.persist(movie);
        return movie;
    }

    /** 일반 OTT 플랫폼 id. */
    private Long platform(String name) {
        Long id = ottPlatformRepository.save(OttPlatform.of(name + SEQ.incrementAndGet())).getId();
        em.flush();
        return id;
    }

    /** 직접 입력('기타') 플랫폼 id — 플래그는 V41 컬럼이라 JDBC로 켠다(엔티티에 수정 메서드를 두지 않았다). 영속성 컨텍스트를 비운다. */
    private Long customPlatform() {
        Long id = platform("직접입력");
        jdbc.update("UPDATE ott_platform SET is_custom = 1 WHERE id = ?", id);
        em.clear();
        return id;
    }

    private WatchRecordResponse add(User user, Movie movie, WatchType type, String placeDetail, Long platformId) {
        return watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(
                movie.getId(), LocalDate.of(2026, 10, 1), type, placeDetail, platformId, null, null));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), user.getRole());
    }

    // ---- ① 새 방식 생성·수정 -------------------------------------------------------------------------

    @Test
    void T1_TV_VOD_DISC로_생성하고_서로_바꿔_수정할_수_있다() {
        User user = user();

        WatchRecordResponse tv = add(user, movie(), WatchType.TV, "OCN", null);
        WatchRecordResponse vod = add(user, movie(), WatchType.VOD, "B tv", null);
        WatchRecordResponse disc = add(user, movie(), WatchType.DISC, null, null);
        em.flush(); // 실제 ENUM 컬럼에 들어가는지

        assertThat(tv.watchType()).isEqualTo(WatchType.TV);
        assertThat(tv.placeDetail()).isEqualTo("OCN");
        assertThat(vod.watchType()).isEqualTo(WatchType.VOD);
        assertThat(disc.watchType()).isEqualTo(WatchType.DISC);

        WatchRecordResponse updated = watchRecordService.updateWatchRecord(user.getId(), tv.id(), new WatchRecordUpdateRequest(
                LocalDate.of(2026, 10, 1), WatchType.DISC, null, null, null, null));
        em.flush();
        assertThat(updated.watchType()).isEqualTo(WatchType.DISC);
        assertThat(jdbc.queryForObject("SELECT watch_type FROM watch_record WHERE id = ?", String.class, tv.id()))
                .isEqualTo("DISC");
    }

    // ---- ② OTT 조합 규칙은 그대로 --------------------------------------------------------------------

    @Test
    void T2_새_방식에_플랫폼을_붙이면_400이고_OTT에_플랫폼이_없어도_400이다() {
        User user = user();
        Movie movie = movie();
        Long platformId = platform("일반OTT");

        for (WatchType type : List.of(WatchType.TV, WatchType.VOD, WatchType.DISC)) {
            assertThatThrownBy(() -> add(user, movie, type, null, platformId)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WATCH_TYPE_OTT_COMBINATION));
        }
        assertThatThrownBy(() -> add(user, movie, WatchType.OTT, null, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WATCH_TYPE_OTT_COMBINATION));
    }

    // ---- ③ 리포트 분포 -------------------------------------------------------------------------------

    @Test
    void T3_리포트_관람_방식_분포에_새_버킷이_코드_변경_없이_나온다() {
        User user = user();
        add(user, movie(), WatchType.TV, null, null);
        add(user, movie(), WatchType.VOD, null, null);
        add(user, movie(), WatchType.DISC, null, null);
        add(user, movie(), WatchType.ETC, null, null);
        em.flush();

        ReportStatisticsResponse report = reportService.getStatistics(user.getId(), user.getId());

        assertThat(report.watchTypeDistribution()).extracting(WatchTypeCountResponse::watchType, WatchTypeCountResponse::count)
                .contains(org.assertj.core.api.Assertions.tuple("TV", 1L), org.assertj.core.api.Assertions.tuple("VOD", 1L),
                        org.assertj.core.api.Assertions.tuple("DISC", 1L), org.assertj.core.api.Assertions.tuple("ETC", 1L));
    }

    // ---- ④ 플랫폼 목록의 custom ----------------------------------------------------------------------

    @Test
    void T4_플랫폼_목록에서_직접_입력_플랫폼만_custom이_true다() throws Exception {
        Long normalId = platform("일반OTT");
        Long customId = customPlatform();

        JsonNode list = JSON.readTree(mockMvc.perform(get("/api/ott-platforms")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        JsonNode normal = find(list, normalId);
        JsonNode custom = find(list, customId);
        assertThat(normal.get("custom").asBoolean()).isFalse();
        assertThat(custom.get("custom").asBoolean()).isTrue();
        // 이름 '기타'로 판별하지 않는다 — 임의 이름의 행도 플래그만으로 custom이다
        assertThat(custom.get("name").asString()).startsWith("직접입력");
    }

    @Test
    void 이름이_기타인_기존_행은_마이그레이션이_모두_custom으로_표시했다() {
        Integer unflagged = jdbc.queryForObject("SELECT COUNT(*) FROM ott_platform WHERE name = '기타' AND is_custom = 0", Integer.class);

        assertThat(unflagged).isZero();
    }

    private static JsonNode find(JsonNode array, Long id) {
        for (JsonNode node : array) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        throw new AssertionError("플랫폼 " + id + "이(가) 목록에 없다");
    }

    // ---- ⑤ OTT + 직접 입력 + placeDetail -------------------------------------------------------------

    @Test
    void T5_직접_입력_플랫폼과_서비스_이름이_함께_저장되고_조회된다() {
        Long customId = customPlatform();
        User user = user();
        Movie movie = movie();

        WatchRecordResponse created = add(user, movie, WatchType.OTT, "라프텔", customId);
        em.flush();
        em.clear();

        WatchRecordResponse logged = watchRecordService.getWatchLog(user.getId(), user.getId(), movie.getId()).get(0);
        assertThat(logged.id()).isEqualTo(created.id());
        assertThat(logged.placeDetail()).isEqualTo("라프텔");
        assertThat(logged.ottPlatform().id()).isEqualTo(customId);
        assertThat(logged.ottPlatform().custom()).isTrue();
    }

    @Test
    void 직접_입력_이름이_비어도_저장된다_서버는_검증하지_않는다() {
        Long customId = customPlatform();
        User user = user();

        WatchRecordResponse created = add(user, movie(), WatchType.OTT, null, customId);

        assertThat(created.placeDetail()).isNull();
        assertThat(created.ottPlatform().custom()).isTrue();
    }

    // ---- ⑥ 피드의 ottPlatformCustom -----------------------------------------------------------------

    @Test
    void T6_피드의_ottPlatformCustom은_직접_입력_플랫폼일_때만_true다() throws Exception {
        Long customId = customPlatform();
        Long normalId = platform("일반OTT");
        User user = user();
        WatchRecordResponse custom = add(user, movie(), WatchType.OTT, "라프텔", customId);
        WatchRecordResponse normal = add(user, movie(), WatchType.OTT, null, normalId);
        WatchRecordResponse tv = add(user, movie(), WatchType.TV, "OCN", null);
        em.flush();
        // 피드는 created_at 기준 — 요청 시각의 경계에 걸리지 않게 조금 과거로 둔다
        for (WatchRecordResponse r : List.of(custom, normal, tv)) {
            jdbc.update("UPDATE watch_record SET created_at = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusHours(1)), r.id());
        }
        em.clear();

        JsonNode page = JSON.readTree(mockMvc.perform(get("/api/users/" + user.getId() + "/activities")
                        .header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        int checked = 0;
        for (JsonNode activity : page.get("content")) {
            JsonNode watch = activity.get("watch");
            if (watch == null || watch.isNull()) {
                continue;
            }
            // 키가 항상 있어야 한다(앱의 생성 타입이 non-optional boolean으로 만든다)
            assertThat(watch.has("ottPlatformCustom")).isTrue();
            long sourceId = activity.get("sourceId").asLong();
            boolean expected = sourceId == custom.id();
            assertThat(watch.get("ottPlatformCustom").asBoolean()).as("기록 %d", sourceId).isEqualTo(expected);
            if (sourceId == tv.id()) {
                assertThat(watch.get("watchType").asString()).isEqualTo("TV");
            }
            checked++;
        }
        assertThat(checked).isEqualTo(3);
        assertThat(normal.id()).isNotNull();
    }
}
