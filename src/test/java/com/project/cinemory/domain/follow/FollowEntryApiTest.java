package com.project.cinemory.domain.follow;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 팔로우 진입 F-4 (social-feed-spec 10-4 T-20 ~ T-26). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * 닉네임은 테스트마다 고유한 태그를 붙여 DB에 남아 있는 다른 데이터와 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FollowEntryApiTest {

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
    private UserService userService;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    /** 검색어에 쓰는 고유 태그 — 20자 제한 안에서 접미사를 붙일 수 있게 짧게. */
    private static String tag() {
        return "s" + (SEQ.incrementAndGet() % 100_000_000L);
    }

    private User user(String nickname, PrivacySetting privacy) {
        User user = User.createOAuth("u" + SEQ.incrementAndGet() + "@entry.test", nickname, null);
        user.changePrivacySetting(privacy);
        em.persist(user);
        return user;
    }

    private void stampFollow(Follow follow, LocalDateTime at) {
        em.flush();
        jdbc.update("UPDATE follow SET created_at = ? WHERE id = ?", Timestamp.valueOf(at), follow.getId());
    }

    private Follow follow(User follower, User following) {
        Follow follow = Follow.of(follower, following);
        em.persist(follow);
        return follow;
    }

    private MvcResult perform(String path, Long viewerId) throws Exception {
        em.flush();
        // get(String)은 URI 템플릿으로 다시 인코딩해 미리 인코딩한 값(%25·한글·%3A)이 이중 인코딩된다 — get(URI)는 그대로 쓴다.
        var request = get(java.net.URI.create(path));
        if (viewerId != null) {
            request = request.header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(viewerId,
                    em.find(User.class, viewerId).getRole()));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode getJson(String path, Long viewerId) throws Exception {
        MvcResult result = perform(path, viewerId);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> nicknames(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.get("content").forEach(node -> result.add(node.get("nickname").asString()));
        return result;
    }

    private static String enc(String q) {
        // URLEncoder는 공백을 '+'로 만들지만 URI 쿼리에서 '+'는 공백이 아니라 글자 그대로다 — %20으로 바꾼다
        return java.net.URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---- T-20 ---------------------------------------------------------------------------------------

    @Test
    void T20_신규_가입은_FRIENDS이고_기존_PRIVATE_사용자는_그대로다() {
        User existing = user("기존" + tag(), PrivacySetting.PRIVATE);

        var local = userService.signUpLocal(new SignUpLocalRequest("local" + SEQ.incrementAndGet() + "@entry.test", "Passw0rd!x", "로컬" + tag()));
        User oauth = userService.signUpOAuth("oauth" + SEQ.incrementAndGet() + "@entry.test",
                OAuthProvider.KAKAO, "entry-" + SEQ.incrementAndGet()).user();
        em.flush();
        em.clear();

        assertThat(em.find(User.class, local.id()).getPrivacySetting()).isEqualTo(PrivacySetting.FRIENDS);
        assertThat(em.find(User.class, oauth.getId()).getPrivacySetting()).isEqualTo(PrivacySetting.FRIENDS);
        assertThat(em.find(User.class, existing.getId()).getPrivacySetting()).isEqualTo(PrivacySetting.PRIVATE);
    }

    // ---- T-21 ~ T-24 --------------------------------------------------------------------------------

    @Test
    void T21_검색은_PUBLIC_FRIENDS만_PRIVATE_ADMIN_본인은_제외하고_following이_정확하다() throws Exception {
        String t = tag();
        User viewer = user(t + "본인", PrivacySetting.PUBLIC);
        User pub = user(t + "공개", PrivacySetting.PUBLIC);
        User friends = user(t + "친구", PrivacySetting.FRIENDS);
        user(t + "비공개", PrivacySetting.PRIVATE);
        User admin = user(t + "관리자", PrivacySetting.PUBLIC);
        em.flush();
        jdbc.update("UPDATE `user` SET role = 'ADMIN' WHERE id = ?", admin.getId());
        em.clear();
        follow(em.find(User.class, viewer.getId()), em.find(User.class, pub.getId()));

        JsonNode page = getJson("/api/users/search?q=" + enc(t), viewer.getId());

        assertThat(nicknames(page)).containsExactlyInAnyOrder(t + "공개", t + "친구");
        for (JsonNode node : page.get("content")) {
            boolean expected = node.get("userId").asLong() == pub.getId();
            assertThat(node.get("following").asBoolean()).as(node.get("nickname").asString()).isEqualTo(expected);
        }
        assertThat(friends.getId()).isNotNull();
    }

    @Test
    void T22_정렬은_완전_일치_앞부분_포함_순이다() throws Exception {
        String t = tag();
        User viewer = user("뷰어" + tag(), PrivacySetting.PUBLIC);
        // id 순으로는 포함 → 앞부분 → 완전 일치라 정렬 키가 아니면 뒤집힌 순서가 나온다
        user("앞" + t, PrivacySetting.PUBLIC);
        user(t + "뒤", PrivacySetting.PUBLIC);
        user(t, PrivacySetting.PUBLIC);

        JsonNode page = getJson("/api/users/search?q=" + enc(t), viewer.getId());

        assertThat(nicknames(page)).containsExactly(t, t + "뒤", "앞" + t);
    }

    @Test
    void T23_와일드카드_문자는_그대로_일치한다() throws Exception {
        String t = tag();
        User viewer = user("뷰어" + tag(), PrivacySetting.PUBLIC);
        user(t + "50%off", PrivacySetting.PUBLIC);
        user(t + "50xoff", PrivacySetting.PUBLIC);
        user(t + "a_b", PrivacySetting.PUBLIC);
        user(t + "aXb", PrivacySetting.PUBLIC);
        user(t + "a\\b", PrivacySetting.PUBLIC);

        assertThat(nicknames(getJson("/api/users/search?q=" + enc(t + "50%off"), viewer.getId())))
                .containsExactly(t + "50%off");
        assertThat(nicknames(getJson("/api/users/search?q=" + enc(t + "a_b"), viewer.getId())))
                .containsExactly(t + "a_b");
        assertThat(nicknames(getJson("/api/users/search?q=" + enc(t + "a\\b"), viewer.getId())))
                .containsExactly(t + "a\\b");
    }

    @Test
    void T24_검색어_길이_공백_비로그인은_거절된다() throws Exception {
        User viewer = user("뷰어" + tag(), PrivacySetting.PUBLIC);

        assertThat(perform("/api/users/search?q=" + enc("가"), viewer.getId()).getResponse().getStatus()).isEqualTo(400);
        assertThat(perform("/api/users/search?q=" + enc("가".repeat(21)), viewer.getId()).getResponse().getStatus()).isEqualTo(400);
        assertThat(perform("/api/users/search?q=" + enc("   "), viewer.getId()).getResponse().getStatus()).isEqualTo(400);
        assertThat(perform("/api/users/search?q=" + enc("가나"), null).getResponse().getStatus()).isEqualTo(401);
        // 경계: 2자와 20자는 통과한다
        assertThat(perform("/api/users/search?q=" + enc("가나"), viewer.getId()).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform("/api/users/search?q=" + enc("가".repeat(20)), viewer.getId()).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- T-25 ---------------------------------------------------------------------------------------

    @Test
    void T25_팔로워_목록은_최근_팔로우_순이고_페이지를_넘겨도_중복_누락이_없다() throws Exception {
        User target = user("대상" + tag(), PrivacySetting.PUBLIC);
        User viewer = user("뷰어" + tag(), PrivacySetting.PUBLIC);
        List<Long> followerIdsInFollowOrder = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            User follower = user("팔로워" + i + tag(), PrivacySetting.PUBLIC);
            follow(follower, target);
            followerIdsInFollowOrder.add(follower.getId());
        }

        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode node = getJson("/api/users/" + target.getId() + "/followers?page=" + page + "&size=20", viewer.getId());
            node.get("content").forEach(n -> seen.add(n.get("userId").asLong()));
        }

        List<Long> expected = new ArrayList<>(followerIdsInFollowOrder);
        java.util.Collections.reverse(expected);
        assertThat(seen).hasSize(45).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
    }

    // ---- T-26 ---------------------------------------------------------------------------------------

    @Test
    void T26_followersSeenAfter_이후_팔로우만_세고_첫_페이지에만_싣는다() throws Exception {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        User viewer = user("뷰어" + tag(), PrivacySetting.PUBLIC);
        // 새 팔로워 2명(이후)·옛 팔로워 1명(이전) — 팔로워의 공개 범위는 따지지 않는다(PRIVATE도 센다)
        stampFollow(follow(user("새1" + tag(), PrivacySetting.PUBLIC), viewer), now.minusMinutes(30));
        stampFollow(follow(user("새2" + tag(), PrivacySetting.PRIVATE), viewer), now.minusMinutes(10));
        stampFollow(follow(user("옛" + tag(), PrivacySetting.PUBLIC), viewer), now.minusHours(2));
        // 2페이지가 생기도록 팔로잉 12명 + 각자의 활동(30일 창 안)
        for (int i = 0; i < 12; i++) {
            User followee = user("팔로잉" + i + tag(), PrivacySetting.PUBLIC);
            follow(viewer, followee);
            Movie movie = Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("영화").build();
            em.persist(movie);
            em.persist(WatchRecord.builder().user(followee).movie(movie).watchDate(now.toLocalDate())
                    .watchType(WatchType.ETC).rating(BigDecimal.valueOf(8)).build());
        }
        String since = "&followersSeenAfter=" + enc(now.minusHours(1).toString());

        JsonNode first = getJson("/api/feed/strips?size=10" + since, viewer.getId());
        assertThat(first.get("newFollowerCount").asLong()).isEqualTo(2);
        assertThat(first.get("hasNext").asBoolean()).isTrue();

        JsonNode second = getJson("/api/feed/strips?size=10&cursor=" + enc(first.get("nextCursor").asString()) + since, viewer.getId());
        assertThat(second.get("newFollowerCount") == null || second.get("newFollowerCount").isNull()).isTrue();

        // 파라미터가 없으면 첫 페이지에도 싣지 않는다
        JsonNode without = getJson("/api/feed/strips?size=10", viewer.getId());
        assertThat(without.get("newFollowerCount") == null || without.get("newFollowerCount").isNull()).isTrue();
    }
}
