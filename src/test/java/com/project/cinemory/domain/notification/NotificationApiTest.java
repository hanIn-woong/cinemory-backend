package com.project.cinemory.domain.notification;

import com.project.cinemory.domain.follow.service.FollowService;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 팔로우 알림 N-T1 ~ N-T11, N-T14 (notification-spec 5절). 실 DB({@code cinemory_test}), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationApiTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private FollowService followService;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User user(String nickname) {
        User user = User.createOAuth("n" + SEQ.incrementAndGet() + "@noti.test", nickname, null);
        em.persist(user);
        return user;
    }

    private long count(String sql, Object... args) {
        em.flush();
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private long notifications(User recipient) {
        return count("SELECT COUNT(*) FROM notification WHERE user_id = ?", recipient.getId());
    }

    private String bearer(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId, em.find(User.class, userId).getRole());
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Long viewerId) throws Exception {
        em.flush();
        if (viewerId != null) {
            request = request.header("Authorization", bearer(viewerId));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode list(Long viewerId, String query) throws Exception {
        MvcResult result = call(get("/api/notifications" + query), viewerId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private List<Long> notificationIds(User recipient) {
        em.flush();
        return jdbc.queryForList("SELECT id FROM notification WHERE user_id = ? ORDER BY id", Long.class, recipient.getId());
    }

    private int markRead(Long viewerId, String body) throws Exception {
        return call(post("/api/notifications/read").contentType(MediaType.APPLICATION_JSON).content(body), viewerId)
                .getResponse().getStatus();
    }

    // ---- 생성 · 삭제 --------------------------------------------------------------------------------

    @Test
    void NT1_팔로우하면_상대에게_FOLLOW_알림_1건이_생긴다() throws Exception {
        User a = user("에이");
        User b = user("비");

        followService.follow(a.getId(), b.getId());
        em.flush();

        assertThat(notifications(b)).isEqualTo(1);
        assertThat(notifications(a)).isZero();
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND actor_id = ? AND type = 'FOLLOW' "
                + "AND target_type IS NULL AND target_id IS NULL AND is_read = 0", b.getId(), a.getId())).isEqualTo(1);
        JsonNode count = JSON.readTree(call(get("/api/notifications/unread-count"), b.getId())
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(count.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void NT2_이미_팔로우_중이면_알림이_늘지_않는다() {
        User a = user("에이");
        User b = user("비");

        followService.follow(a.getId(), b.getId());
        followService.follow(a.getId(), b.getId());

        assertThat(notifications(b)).isEqualTo(1);
    }

    @Test
    void NT3_자기_자신_팔로우는_거부되고_알림이_없다() {
        User a = user("에이");

        assertThatThrownBy(() -> followService.follow(a.getId(), a.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANNOT_FOLLOW_SELF));
        assertThat(notifications(a)).isZero();
    }

    @Test
    void NT4_언팔로우하면_읽음_여부와_무관하게_그_알림만_지워진다() {
        User a = user("에이");
        User b = user("비");
        User c = user("씨");
        User d = user("디");
        followService.follow(a.getId(), b.getId());
        followService.follow(c.getId(), b.getId());
        followService.follow(d.getId(), b.getId());
        // a의 알림은 읽음, d의 알림은 안 읽음 상태로 두고 둘 다 언팔로우
        jdbc.update("UPDATE notification SET is_read = 1 WHERE user_id = ? AND actor_id = ?", b.getId(), a.getId());

        followService.unfollow(a.getId(), b.getId());
        followService.unfollow(d.getId(), b.getId());

        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND actor_id = ?", b.getId(), a.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND actor_id = ?", b.getId(), d.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND actor_id = ?", b.getId(), c.getId())).isEqualTo(1);
    }

    @Test
    void NT5_팔로우하지_않은_상태의_언팔로우는_알림을_건드리지_않는다() {
        User a = user("에이");
        User b = user("비");
        User c = user("씨");
        followService.follow(c.getId(), b.getId());

        followService.unfollow(a.getId(), b.getId());

        assertThat(notifications(b)).isEqualTo(1);
    }

    @Test
    void NT6_언팔로우_후_다시_팔로우하면_새_알림_1건이다() {
        User a = user("에이");
        User b = user("비");
        followService.follow(a.getId(), b.getId());
        Long firstId = notificationIds(b).get(0);

        followService.unfollow(a.getId(), b.getId());
        followService.follow(a.getId(), b.getId());

        List<Long> after = notificationIds(b);
        assertThat(after).hasSize(1);
        assertThat(after.get(0)).isNotEqualTo(firstId);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND is_read = 0", b.getId())).isEqualTo(1);
    }

    // ---- 조회 ---------------------------------------------------------------------------------------

    @Test
    void NT7_45건을_20개씩_3페이지로_중복_누락_없이_id_내림차순으로_읽는다() throws Exception {
        User b = user("받는이");
        for (int i = 0; i < 45; i++) {
            followService.follow(user("팔로워" + i).getId(), b.getId());
        }
        List<Long> expected = new ArrayList<>(notificationIds(b));
        java.util.Collections.reverse(expected);

        List<Long> collected = new ArrayList<>();
        JsonNode page1 = list(b.getId(), "");
        assertThat(page1.get("hasNext").asBoolean()).isTrue();
        collected.addAll(ids(page1));
        JsonNode page2 = list(b.getId(), "?cursor=" + page1.get("nextCursor").asLong() + "&size=20");
        assertThat(page2.get("hasNext").asBoolean()).isTrue();
        collected.addAll(ids(page2));
        JsonNode page3 = list(b.getId(), "?cursor=" + page2.get("nextCursor").asLong() + "&size=20");

        collected.addAll(ids(page3));
        assertThat(page3.get("hasNext").asBoolean()).isFalse();
        assertThat(page3.get("nextCursor").isNull()).isTrue();
        assertThat(collected).containsExactlyElementsOf(expected);
    }

    @Test
    void NT8_첫_페이지_조회_뒤_새_알림이_와도_다음_페이지에_중복이_없다() throws Exception {
        User b = user("받는이");
        for (int i = 0; i < 5; i++) {
            followService.follow(user("팔로워" + i).getId(), b.getId());
        }
        JsonNode page1 = list(b.getId(), "?size=3");

        followService.follow(user("새팔로워").getId(), b.getId());
        JsonNode page2 = list(b.getId(), "?size=3&cursor=" + page1.get("nextCursor").asLong());

        assertThat(ids(page2)).doesNotContainAnyElementsOf(ids(page1)).hasSize(2);
        assertThat(page2.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void NT9_행위자를_내가_팔로우_중인지가_following에_담긴다() throws Exception {
        User b = user("받는이");
        User mutual = user("맞팔");
        User oneWay = user("단방향");
        followService.follow(mutual.getId(), b.getId());
        followService.follow(oneWay.getId(), b.getId());
        followService.follow(b.getId(), mutual.getId());

        JsonNode page = list(b.getId(), "");

        for (JsonNode n : page.get("content")) {
            JsonNode actor = n.get("actor");
            assertThat(actor.get("following").asBoolean()).isEqualTo(actor.get("userId").asLong() == mutual.getId());
            assertThat(n.get("type").asString()).isEqualTo("FOLLOW");
            assertThat(n.get("read").asBoolean()).isFalse();
        }
        assertThat(page.get("content")).hasSize(2);
    }

    // ---- 읽음 ---------------------------------------------------------------------------------------

    @Test
    void NT10_upToId_이하만_읽음이_되고_최신은_남는다() throws Exception {
        User b = user("받는이");
        for (int i = 0; i < 3; i++) {
            followService.follow(user("팔로워" + i).getId(), b.getId());
        }
        List<Long> ids = notificationIds(b);

        assertThat(markRead(b.getId(), "{\"upToId\":" + ids.get(1) + "}")).isEqualTo(204);

        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND is_read = 1 AND id <= ?", b.getId(), ids.get(1))).isEqualTo(2);
        assertThat(count("SELECT is_read FROM notification WHERE id = ?", ids.get(2))).isZero();
        JsonNode count = JSON.readTree(call(get("/api/notifications/unread-count"), b.getId())
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(count.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void NT11_남의_알림_id를_보내도_204이고_남의_알림은_그대로다() throws Exception {
        User a = user("에이");
        User b = user("비");
        User c = user("씨");
        followService.follow(a.getId(), b.getId());
        followService.follow(c.getId(), a.getId());
        Long othersId = notificationIds(b).get(0);

        assertThat(markRead(a.getId(), "{\"upToId\":" + othersId + "}")).isEqualTo(204);

        assertThat(count("SELECT is_read FROM notification WHERE id = ?", othersId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND is_read = 0", a.getId())).isEqualTo(1);
    }

    // ---- 입력 · 인증 --------------------------------------------------------------------------------

    @Test
    void NT14_비로그인은_401이고_size_범위와_upToId_누락은_400이다() throws Exception {
        User a = user("에이");

        assertThat(call(get("/api/notifications"), null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/api/notifications/unread-count"), null).getResponse().getStatus()).isEqualTo(401);
        assertThat(markRead(null, "{\"upToId\":1}")).isEqualTo(401);

        assertThat(call(get("/api/notifications?size=0"), a.getId()).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(get("/api/notifications?size=51"), a.getId()).getResponse().getStatus()).isEqualTo(400);
        assertThat(markRead(a.getId(), "{}")).isEqualTo(400);
        assertThat(markRead(a.getId(), "{\"upToId\":0}")).isEqualTo(400);
    }
}
