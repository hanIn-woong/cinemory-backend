package com.project.cinemory.domain.feed;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.feed.entity.ActivityType;
import com.project.cinemory.domain.feed.repository.ActivityRow;
import com.project.cinemory.domain.feed.service.ActivityAssembler;
import com.project.cinemory.domain.feed.service.StripOrderCache;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.global.access.UserAccessPolicy;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소셜 피드 F-1 (social-feed-spec 7절 T-1 ~ T-14). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * 활동 시각은 {@code created_at}을 JDBC로 직접 찍어 만든다 — 기간·동점·커서 경계를 결정적으로 만들기 위해서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedApiTest {

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
    private UserAccessPolicy userAccessPolicy;
    @Autowired
    private ActivityAssembler activityAssembler;
    @Autowired
    private StripOrderCache stripOrderCache;

    /** 대부분의 활동이 놓이는 기준 시각 — 30일 창 안쪽이고 asOf(지금) 이전이다. */
    private final LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(2);

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User user(String tag, PrivacySetting privacy) {
        User user = User.createOAuth(tag + "-" + SEQ.incrementAndGet() + "@feed.test", tag, null);
        user.changePrivacySetting(privacy);
        em.persist(user);
        return user;
    }

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("피드 테스트 영화").build();
        em.persist(movie);
        return movie;
    }

    private void stamp(String table, Long id, LocalDateTime at) {
        em.flush();
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", Timestamp.valueOf(at), id);
    }

    private WatchRecord watch(User user, LocalDateTime at) {
        return watch(user, movie(), at);
    }

    private WatchRecord watch(User user, Movie movie, LocalDateTime at) {
        WatchRecord record = WatchRecord.builder().user(user).movie(movie).watchDate(at.toLocalDate())
                .watchType(WatchType.ETC).rating(BigDecimal.valueOf(8)).privateReview("나만 보는 메모").build();
        em.persist(record);
        stamp("watch_record", record.getId(), at);
        return record;
    }

    private Review review(User user, LocalDateTime at) {
        Review review = Review.of(user, movie(), "리뷰 본문");
        em.persist(review);
        stamp("review", review.getId(), at);
        return review;
    }

    private WishMovie wish(User user, LocalDateTime at) {
        WishMovie wish = WishMovie.of(user, movie());
        em.persist(wish);
        stamp("wish_movie", wish.getId(), at);
        return wish;
    }

    private Collection collection(User user, LocalDateTime at) {
        Collection collection = Collection.builder().user(user).name("컬렉션").position(0).build();
        em.persist(collection);
        stamp("collection", collection.getId(), at);
        return collection;
    }

    private CollectionMovie addToCollection(Collection collection, LocalDateTime at) {
        CollectionMovie item = CollectionMovie.of(collection, movie(), 0);
        em.persist(item);
        stamp("collection_movie", item.getId(), at);
        return item;
    }

    private Follow follow(User follower, User following, LocalDateTime at) {
        Follow follow = Follow.of(follower, following);
        em.persist(follow);
        stamp("follow", follow.getId(), at);
        return follow;
    }

    private JsonNode getJson(String path, Long viewerId) throws Exception {
        return JSON.readTree(perform(path, viewerId).getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult perform(String path, Long viewerId) throws Exception {
        em.flush();
        var request = get(path);
        if (viewerId != null) {
            request = request.header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(viewerId,
                    em.find(User.class, viewerId).getRole()));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static List<String> types(JsonNode content) {
        List<String> result = new ArrayList<>();
        content.forEach(node -> result.add(node.get("type").asString()));
        return result;
    }

    private static List<Long> stripUserIds(JsonNode strips) {
        List<Long> result = new ArrayList<>();
        strips.get("content").forEach(node -> result.add(node.get("user").get("userId").asLong()));
        return result;
    }

    private static String cursorOf(JsonNode page) {
        return page.get("nextCursor").asString();
    }

    private static String encodeCursor(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ---- T-1 · T-2 ----------------------------------------------------------------------------------

    @Test
    void T1_여섯_종류_활동이_모두_나오고_시각_내림차순이다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        User followee = user("followee", PrivacySetting.PUBLIC);
        Collection collection = collection(actor, base.minusDays(3));         // 생성일과 추가일이 달라야 5번이 생긴다
        addToCollection(collection, base.plusHours(1));
        review(actor, base.plusHours(2));
        watch(actor, base.plusHours(3));
        wish(actor, base.plusHours(4));
        follow(actor, followee, base.plusHours(5));

        JsonNode page = getJson("/api/users/" + actor.getId() + "/activities", viewer.getId());

        assertThat(types(page.get("content"))).containsExactly("FOLLOW", "WISH", "WATCH_RECORD", "REVIEW",
                "COLLECTION_MOVIES_ADDED", "COLLECTION_CREATED");
        assertThat(page.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void T2_별점_수정과_리뷰_재작성은_순서를_바꾸지_않고_내용만_바꾼다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        WatchRecord record = watch(actor, base.plusHours(1));
        Review review = review(actor, base.plusHours(2));
        wish(actor, base.plusHours(3));
        String path = "/api/users/" + actor.getId() + "/activities";

        JsonNode before = getJson(path, viewer.getId());
        record.update(record.getWatchDate(), WatchType.ETC, null, null, BigDecimal.valueOf(3), null);
        review.update("고쳐 쓴 리뷰");
        em.flush();
        JsonNode after = getJson(path, viewer.getId());

        assertThat(types(after.get("content"))).isEqualTo(types(before.get("content")));
        for (int i = 0; i < 3; i++) {
            assertThat(after.get("content").get(i).get("occurredAt")).isEqualTo(before.get("content").get(i).get("occurredAt"));
        }
        assertThat(after.get("content").get(1).get("reviewContent").asString()).isEqualTo("고쳐 쓴 리뷰");
        assertThat(after.get("content").get(2).get("watch").get("rating").decimalValue()).isEqualByComparingTo("3");
    }

    // ---- T-3 · T-4 ----------------------------------------------------------------------------------

    @Test
    void T3_스트립의_공개_범위가_UserAccessPolicy_canView와_정확히_일치한다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User publicUser = user("public", PrivacySetting.PUBLIC);
        User mutualFriend = user("mutual", PrivacySetting.FRIENDS);
        User oneWayFriend = user("oneway", PrivacySetting.FRIENDS);
        User privateUser = user("private", PrivacySetting.PRIVATE);
        List<User> candidates = List.of(publicUser, mutualFriend, oneWayFriend, privateUser);
        for (User candidate : candidates) {
            follow(viewer, candidate, base);
            wish(candidate, base.plusHours(1));
        }
        follow(mutualFriend, viewer, base);                                  // 맞팔은 mutualFriend만

        JsonNode strips = getJson("/api/feed/strips", viewer.getId());

        Set<Long> expected = new HashSet<>();
        for (User candidate : candidates) {
            if (userAccessPolicy.canView(viewer.getId(), candidate.getId())) {
                expected.add(candidate.getId());
            }
        }
        assertThat(stripUserIds(strips)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).containsExactlyInAnyOrder(publicUser.getId(), mutualFriend.getId());   // 정책 자체의 기대값도 고정
    }

    @Test
    void T4_FOLLOW_대상이_PRIVATE이면_그_프레임만_빠지고_나머지는_남는다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        User visible = user("visible", PrivacySetting.PUBLIC);
        User hidden = user("hidden", PrivacySetting.PRIVATE);
        wish(actor, base.plusHours(1));
        follow(actor, visible, base.plusHours(2));
        follow(actor, hidden, base.plusHours(3));
        follow(viewer, actor, base);

        JsonNode page = getJson("/api/users/" + actor.getId() + "/activities", viewer.getId());
        JsonNode strips = getJson("/api/feed/strips", viewer.getId());

        assertThat(types(page.get("content"))).containsExactly("FOLLOW", "WISH");
        assertThat(page.get("content").get(0).get("followee").get("userId").asLong()).isEqualTo(visible.getId());
        JsonNode frames = strips.get("content").get(0).get("frames");
        // 스트립 프레임에는 팔로우가 없다(영화 기록을 밀어내서) — 개인 티켓 피드와 다른 점
        assertThat(types(frames)).containsExactly("WISH");
    }

    // ---- T-5 · T-6 · T-7 ----------------------------------------------------------------------------

    @Test
    void T5_컬렉션_생성일_추가분은_흡수되고_다음날_추가분만_묶여_나온다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        LocalDateTime createdAt = base.minusDays(5).withHour(10);
        Collection collection = collection(actor, createdAt);
        for (int i = 0; i < 3; i++) {
            addToCollection(collection, createdAt.plusMinutes(i + 1));
        }
        addToCollection(collection, base.withHour(9));
        addToCollection(collection, base.withHour(11));

        JsonNode page = getJson("/api/users/" + actor.getId() + "/activities", viewer.getId());

        assertThat(types(page.get("content"))).containsExactly("COLLECTION_MOVIES_ADDED", "COLLECTION_CREATED");
        JsonNode added = page.get("content").get(0);
        assertThat(added.get("added").get("count").asInt()).isEqualTo(2);
        assertThat(added.get("added").get("groupDate").asString()).isEqualTo(base.toLocalDate().toString());
        assertThat(added.get("occurredAt").asString()).startsWith(base.withHour(11).toLocalDate().toString());
        assertThat(added.get("collection").get("movieCount").asInt()).isEqualTo(5);   // 지금 상태
    }

    @Test
    void T6_컬렉션_삭제_언팔로우_찜_해제_탈퇴는_정리_코드_없이_프레임이_사라진다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        User leaver = user("leaver", PrivacySetting.PUBLIC);
        User followee = user("followee", PrivacySetting.PUBLIC);
        follow(viewer, actor, base);
        follow(viewer, leaver, base);
        Collection collection = collection(actor, base.plusHours(1));
        WishMovie wish = wish(actor, base.plusHours(2));
        Follow follow = follow(actor, followee, base.plusHours(3));
        wish(leaver, base.plusHours(4));
        String path = "/api/users/" + actor.getId() + "/activities";

        assertThat(types(getJson(path, viewer.getId()).get("content"))).containsExactly("FOLLOW", "WISH", "COLLECTION_CREATED");
        assertThat(stripUserIds(getJson("/api/feed/strips", viewer.getId()))).containsExactly(leaver.getId(), actor.getId());

        jdbc.update("DELETE FROM collection WHERE id = ?", collection.getId());
        jdbc.update("DELETE FROM wish_movie WHERE id = ?", wish.getId());
        jdbc.update("DELETE FROM follow WHERE id = ?", follow.getId());
        jdbc.update("DELETE FROM `user` WHERE id = ?", leaver.getId());                 // 탈퇴 — FK CASCADE
        em.clear();

        assertThat(getJson(path, viewer.getId()).get("content")).isEmpty();
        assertThat(stripUserIds(getJson("/api/feed/strips", viewer.getId()))).isEmpty();
    }

    @Test
    void T7_31일_전_활동만_있으면_스트립에서는_빠지고_개인_피드에는_있다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User old = user("old", PrivacySetting.PUBLIC);
        User recent = user("recent", PrivacySetting.PUBLIC);
        follow(viewer, old, base.minusDays(40));
        follow(viewer, recent, base.minusDays(40));
        wish(old, LocalDateTime.now().minusDays(31));
        wish(recent, LocalDateTime.now().minusDays(29));

        JsonNode strips = getJson("/api/feed/strips", viewer.getId());
        JsonNode feed = getJson("/api/users/" + old.getId() + "/activities", viewer.getId());

        assertThat(stripUserIds(strips)).containsExactly(recent.getId());
        assertThat(types(feed.get("content"))).containsExactly("WISH");
    }

    // ---- T-8 · T-9 ----------------------------------------------------------------------------------

    @Test
    void T8_asOf_이후_활동은_다음_페이지에서_무시되어_스트립이_중복도_누락도_없다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        List<User> followees = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            User followee = user("f" + i, PrivacySetting.PUBLIC);
            follow(viewer, followee, base.minusDays(10));
            wish(followee, base.minusHours(i));                                          // f0이 가장 최근
            followees.add(followee);
        }

        JsonNode first = getJson("/api/feed/strips?size=2", viewer.getId());
        LocalDateTime asOf = LocalDateTime.parse(first.get("asOf").asString());
        wish(followees.get(2), asOf.plusHours(1));                                       // 2페이지에 있어야 할 사람이 첫 페이지 이후 활동
        JsonNode second = getJson("/api/feed/strips?size=2&cursor=" + cursorOf(first), viewer.getId());

        List<Long> seen = new ArrayList<>(stripUserIds(first));
        seen.addAll(stripUserIds(second));
        assertThat(seen).containsExactly(followees.get(0).getId(), followees.get(1).getId(),
                followees.get(2).getId(), followees.get(3).getId());
        assertThat(second.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void T8_개인_피드도_asOf_이후_활동에_흔들리지_않는다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        for (int i = 0; i < 5; i++) {
            wish(actor, base.minusHours(i));
        }
        String path = "/api/users/" + actor.getId() + "/activities?size=2";

        JsonNode first = getJson(path, viewer.getId());
        wish(actor, LocalDateTime.parse(first.get("asOf").asString()).plusHours(1));
        JsonNode second = getJson(path + "&cursor=" + cursorOf(first), viewer.getId());
        JsonNode third = getJson(path + "&cursor=" + cursorOf(second), viewer.getId());

        List<Long> ids = new ArrayList<>();
        for (JsonNode page : List.of(first, second, third)) {
            page.get("content").forEach(node -> ids.add(node.get("sourceId").asLong()));
        }
        assertThat(ids).hasSize(5).doesNotHaveDuplicates();
        assertThat(third.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void T9_같은_초_활동도_페이지_경계에서_중복도_누락도_없다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        LocalDateTime tie = base.plusHours(1);
        for (int i = 0; i < 3; i++) {
            watch(actor, tie);
        }
        for (int i = 0; i < 2; i++) {
            wish(actor, tie);
            review(actor, tie);
        }
        String path = "/api/users/" + actor.getId() + "/activities?size=3";

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = getJson(cursor == null ? path : path + "&cursor=" + cursor, viewer.getId());
            page.get("content").forEach(node -> seen.add(node.get("type").asString() + ":" + node.get("sourceId").asLong()));
            cursor = page.get("hasNext").asBoolean() ? cursorOf(page) : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        List<ActivityType> typeOrder = seen.stream().map(s -> ActivityType.valueOf(s.substring(0, s.indexOf(':')))).toList();
        assertThat(typeOrder).isSortedAccordingTo(Comparator.comparingInt(ActivityType::sortKey).reversed());
    }

    @Test
    void T9_스트립도_마지막_활동이_같은_초여도_경계에서_안정적이다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User followee = user("t" + i, PrivacySetting.PUBLIC);
            follow(viewer, followee, base.minusDays(10));
            wish(followee, base);
            ids.add(followee.getId());
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = getJson("/api/feed/strips?size=2" + (cursor == null ? "" : "&cursor=" + cursor), viewer.getId());
            seen.addAll(stripUserIds(page));
            cursor = page.get("hasNext").asBoolean() ? cursorOf(page) : null;
        } while (cursor != null);

        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(seen).isSortedAccordingTo(Comparator.<Long>naturalOrder().reversed());   // 동점은 userId 내림차순
    }

    // ---- T-10 · T-11 · T-12 · T-13 · T-14 -----------------------------------------------------------

    @Test
    void T10_잘못된_커서와_scope_ALL은_400_INVALID_INPUT_VALUE다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        String stripCursor = encodeCursor("{\"asOf\":\"2026-10-29T10:00:00\",\"at\":\"2026-10-29T09:00:00\",\"uid\":1}");
        List<String> bad = List.of("!!!", encodeCursor("not json"), encodeCursor("{}"), encodeCursor("[]"), "e30");

        for (String cursor : bad) {
            for (String path : List.of("/api/feed/strips?cursor=", "/api/users/" + viewer.getId() + "/activities?cursor=")) {
                MvcResult result = perform(path + cursor, viewer.getId());
                assertThat(result.getResponse().getStatus()).as(path + cursor).isEqualTo(400);
                assertThat(JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code").asString())
                        .isEqualTo("INVALID_INPUT_VALUE");
            }
        }
        // 스트립 커서를 피드에 보내면 필드(k·id)가 없어 거절된다
        assertThat(perform("/api/users/" + viewer.getId() + "/activities?cursor=" + stripCursor, viewer.getId())
                .getResponse().getStatus()).isEqualTo(400);

        MvcResult all = perform("/api/feed/strips?scope=ALL", viewer.getId());
        assertThat(all.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(all.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code").asString())
                .isEqualTo("INVALID_INPUT_VALUE");
        assertThat(perform("/api/feed/strips?seenAfter=어제", viewer.getId()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void T11_비로그인은_두_API_모두_401이다() throws Exception {
        User actor = user("actor", PrivacySetting.PUBLIC);
        em.flush();

        mockMvc.perform(get("/api/feed/strips")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/" + actor.getId() + "/activities")).andExpect(status().isUnauthorized());
    }

    @Test
    void T12_응답에_private_review가_없다_내_피드에서도() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User actor = user("actor", PrivacySetting.PUBLIC);
        watch(actor, base);
        watch(viewer, base);
        follow(viewer, actor, base.minusDays(1));

        List<String> bodies = List.of(
                perform("/api/users/" + actor.getId() + "/activities", viewer.getId()).getResponse().getContentAsString(StandardCharsets.UTF_8),
                perform("/api/users/" + viewer.getId() + "/activities", viewer.getId()).getResponse().getContentAsString(StandardCharsets.UTF_8),
                perform("/api/feed/strips", viewer.getId()).getResponse().getContentAsString(StandardCharsets.UTF_8));

        for (String body : bodies) {
            assertThat(body).contains("WATCH_RECORD");
            assertThat(body).doesNotContain("privateReview").doesNotContain("private_review").doesNotContain("나만 보는 메모");
        }
    }

    @Test
    void T13_me는_첫_페이지에만_있다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        for (int i = 0; i < 2; i++) {
            User followee = user("f" + i, PrivacySetting.PUBLIC);
            follow(viewer, followee, base.minusDays(10));
            wish(followee, base.minusHours(i));
        }

        JsonNode first = getJson("/api/feed/strips?size=1", viewer.getId());
        JsonNode second = getJson("/api/feed/strips?size=1&cursor=" + cursorOf(first), viewer.getId());

        assertThat(first.get("me").get("user").get("userId").asLong()).isEqualTo(viewer.getId());
        assertThat(types(first.get("me").get("frames"))).isEmpty();   // 팔로우는 스트립 프레임에서 뺀다 — 개인 티켓 피드에서만 보인다
        assertThat(second.get("me") == null || second.get("me").isNull()).isTrue();
    }

    @Test
    void T13_활동이_전혀_없어도_me는_frames_빈_배열로_항상_실린다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);

        JsonNode strips = getJson("/api/feed/strips", viewer.getId());

        assertThat(strips.get("me").get("user").get("userId").asLong()).isEqualTo(viewer.getId());
        assertThat(strips.get("me").get("frames")).isEmpty();
        assertThat(strips.get("me").get("lastActivityAt").isNull()).isTrue();
        assertThat(strips.get("content")).isEmpty();

        wish(viewer, base);
        assertThat(types(getJson("/api/feed/strips", viewer.getId()).get("me").get("frames"))).containsExactly("WISH");
    }

    @Test
    void T14_seenAfter가_있으면_그_이후_활동_수가_newCount로_나온다_30일_창_안에서만() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User followee = user("followee", PrivacySetting.PUBLIC);
        follow(viewer, followee, base.minusDays(60));
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        wish(followee, now.minusDays(1));
        wish(followee, now.minusDays(2));
        wish(followee, now.minusDays(10));
        wish(followee, now.minusDays(40));                                                // 창 밖 — 세지 않는다
        String seenAfter = now.minusDays(5).toString();

        JsonNode with = getJson("/api/feed/strips?seenAfter=" + seenAfter, viewer.getId());
        JsonNode without = getJson("/api/feed/strips", viewer.getId());

        assertThat(with.get("content").get(0).get("newCount").asInt()).isEqualTo(2);
        assertThat(with.get("content").get(0).get("frames")).hasSize(3);
        assertThat(without.get("content").get(0).get("newCount").asInt()).isZero();
        // JS toISOString()처럼 UTC(Z)로 와도 서버 시간대로 옮겨 같은 결과가 나온다
        String utc = now.minusDays(5).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toString();
        assertThat(getJson("/api/feed/strips?seenAfter=" + utc, viewer.getId()).get("content").get(0).get("newCount").asInt())
                .isEqualTo(2);
    }

    // ---- T-15 · T-16 · T-17 (4-2 개정: 순위 캐시) ----------------------------------------------------

    /** 첫 페이지 응답의 커서로 끝까지 넘기며 사람 순서를 모은다. */
    private List<Long> walkStrips(JsonNode first, Long viewerId, int size) throws Exception {
        List<Long> seen = new ArrayList<>(stripUserIds(first));
        JsonNode page = first;
        int guard = 0;
        while (page.get("hasNext").asBoolean() && guard++ < 20) {
            page = getJson("/api/feed/strips?size=" + size + "&cursor=" + cursorOf(page), viewerId);
            seen.addAll(stripUserIds(page));
        }
        return seen;
    }

    @Test
    void T15_캐시_적중_경로와_캐시_미스_대체_경로의_사람_순서가_완전히_같다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        for (int i = 0; i < 7; i++) {
            User followee = user("f" + i, PrivacySetting.PUBLIC);
            follow(viewer, followee, base.minusDays(10));
            wish(followee, base.minusHours(i / 2));                                       // 둘씩 같은 시각(동점)
            if (i % 3 == 0) {
                review(followee, base.minusHours(i / 2).minusMinutes(1));                  // 다른 갈래의 더 오래된 활동
            }
        }

        JsonNode first = getJson("/api/feed/strips?size=2", viewer.getId());
        LocalDateTime asOf = LocalDateTime.parse(first.get("asOf").asString());
        assertThat(stripOrderCache.get(viewer.getId(), asOf)).as("첫 페이지가 순서를 캐시에 넣는다").hasSize(7);
        List<Long> hit = walkStrips(first, viewer.getId(), 2);

        stripOrderCache.clear();
        assertThat(stripOrderCache.get(viewer.getId(), asOf)).isNull();
        List<Long> miss = new ArrayList<>(stripUserIds(first));
        JsonNode page = getJson("/api/feed/strips?size=2&cursor=" + cursorOf(first), viewer.getId());
        miss.addAll(stripUserIds(page));
        while (page.get("hasNext").asBoolean()) {
            page = getJson("/api/feed/strips?size=2&cursor=" + cursorOf(page), viewer.getId());
            miss.addAll(stripUserIds(page));
        }

        assertThat(hit).hasSize(7).doesNotHaveDuplicates();
        assertThat(miss).isEqualTo(hit);
        assertThat(stripOrderCache.get(viewer.getId(), asOf)).as("미스 대체 경로는 캐시를 채우지 않는다").isNull();
    }

    @Test
    void T16_첫_페이지_뒤_PRIVATE으로_바뀌거나_탈퇴한_사람은_캐시_적중_페이지에서_빠진다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        List<User> followees = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User followee = user("f" + i, PrivacySetting.PUBLIC);
            follow(viewer, followee, base.minusDays(10));
            wish(followee, base.minusHours(i));                                          // f0이 가장 최근
            followees.add(followee);
        }

        JsonNode first = getJson("/api/feed/strips?size=2", viewer.getId());
        assertThat(stripUserIds(first)).containsExactly(followees.get(0).getId(), followees.get(1).getId());

        followees.get(2).changePrivacySetting(PrivacySetting.PRIVATE);
        em.flush();
        jdbc.update("DELETE FROM `user` WHERE id = ?", followees.get(4).getId());          // 탈퇴 — FK CASCADE
        em.clear();

        JsonNode second = getJson("/api/feed/strips?size=2&cursor=" + cursorOf(first), viewer.getId());
        assertThat(stripUserIds(second)).containsExactly(followees.get(3).getId());        // 줄어든 페이지를 그대로 보낸다
        assertThat(second.get("hasNext").asBoolean()).isTrue();                            // hasNext는 캐시 목록 기준

        JsonNode third = getJson("/api/feed/strips?size=2&cursor=" + cursorOf(second), viewer.getId());
        assertThat(third.get("content")).isEmpty();
        assertThat(third.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void T17_첫_페이지_집계는_갈래_밖_HAVING으로_31일_전_마지막_활동을_제외한다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User mixed = user("mixed", PrivacySetting.PUBLIC);
        User stale = user("stale", PrivacySetting.PUBLIC);
        follow(viewer, mixed, base.minusDays(60));
        follow(viewer, stale, base.minusDays(60));
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        wish(mixed, now.minusDays(31));
        LocalDateTime recentReview = now.minusDays(3);
        review(mixed, recentReview);
        watch(stale, now.minusDays(31));
        wish(stale, now.minusDays(45));

        JsonNode strips = getJson("/api/feed/strips", viewer.getId());

        assertThat(stripUserIds(strips)).containsExactly(mixed.getId());
        assertThat(LocalDateTime.parse(strips.get("content").get(0).get("lastActivityAt").asString())).isEqualTo(recentReview);
        assertThat(types(strips.get("content").get(0).get("frames"))).containsExactly("REVIEW");   // 프레임은 30일 창 그대로
    }

    // ---- 접근 제어 · 프레임 상한 · 조립 -------------------------------------------------------------

    @Test
    void 개인_피드는_PRIVATE_대상이면_403_없는_사용자면_404다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User hidden = user("hidden", PrivacySetting.PRIVATE);

        assertThat(perform("/api/users/" + hidden.getId() + "/activities", viewer.getId()).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform("/api/users/999999999/activities", viewer.getId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform("/api/users/" + viewer.getId() + "/activities", viewer.getId()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void T18_스트립_프레임은_같은_영화_기록을_최신_1건으로_합치고_개인_피드는_건별이다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User followee = user("followee", PrivacySetting.PUBLIC);
        follow(viewer, followee, base.minusDays(10));
        Movie rewatched = movie();
        watch(followee, rewatched, base.plusMinutes(1));
        watch(followee, rewatched, base.plusMinutes(2));
        watch(followee, rewatched, base.plusMinutes(3));
        for (int i = 0; i < 5; i++) {
            watch(followee, base.plusMinutes(10 + i));
        }

        JsonNode strip = getJson("/api/feed/strips?seenAfter=" + base.toString(), viewer.getId()).get("content").get(0);
        JsonNode frames = strip.get("frames");

        // 합친 만큼 뒤의 활동이 올라와 띠가 찬다: 다른 영화 5편 + 그 영화 1장 = 6장
        assertThat(frames).hasSize(6);
        long sameMovie = 0;
        JsonNode merged = null;
        for (JsonNode frame : frames) {
            if (frame.get("movie").get("id").asLong() == rewatched.getId()) {
                sameMovie++;
                merged = frame;
            }
        }
        assertThat(sameMovie).isEqualTo(1);
        assertThat(LocalDateTime.parse(merged.get("occurredAt").asString())).isEqualTo(base.plusMinutes(3)); // toString()은 초가 0이면 ":00"을 생략해 1/60 확률로 어긋난다
        // newCount는 합치기 전 원시 건수 (8건 전부 seenAfter 이후)
        assertThat(strip.get("newCount").asInt()).isEqualTo(8);

        JsonNode personal = getJson("/api/users/" + followee.getId() + "/activities", viewer.getId());
        long rewatchedInPersonal = 0;
        for (JsonNode activity : personal.get("content")) {
            if (activity.get("movie").get("id").asLong() == rewatched.getId()) {
                rewatchedInPersonal++;
            }
        }
        assertThat(rewatchedInPersonal).isEqualTo(3);
    }

    @Test
    void T19_합쳐진_프레임의_recordCount는_30일_창_안_기록_수이고_개인_피드는_null이다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User followee = user("followee", PrivacySetting.PUBLIC);
        follow(viewer, followee, base.minusDays(40));
        Movie rewatched = movie();
        watch(followee, rewatched, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(35)); // 창 밖
        watch(followee, rewatched, base.plusMinutes(1));
        watch(followee, rewatched, base.plusMinutes(2));
        watch(followee, base.plusMinutes(3));                                                           // 단일 기록

        JsonNode frames = getJson("/api/feed/strips", viewer.getId()).get("content").get(0).get("frames");

        assertThat(frames).hasSize(2);
        for (JsonNode frame : frames) {
            int expected = frame.get("movie").get("id").asLong() == rewatched.getId() ? 2 : 1;
            assertThat(frame.get("watch").get("recordCount").asInt()).isEqualTo(expected);
        }
        JsonNode personal = getJson("/api/users/" + followee.getId() + "/activities", viewer.getId());
        for (JsonNode activity : personal.get("content")) {
            assertThat(activity.get("watch").get("recordCount").isNull()).isTrue();
        }
    }

    @Test
    void 영화_합치기는_리뷰_찜과_서로_합치지_않고_me_프레임에도_적용된다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        Movie same = movie();
        watch(viewer, same, base.plusMinutes(1));
        watch(viewer, same, base.plusMinutes(2));
        em.persist(Review.of(viewer, same, "같은 영화 리뷰"));
        em.persist(WishMovie.of(viewer, same));
        em.flush();

        JsonNode me = getJson("/api/feed/strips", viewer.getId()).get("me");

        assertThat(types(me.get("frames"))).containsExactlyInAnyOrder("WATCH_RECORD", "REVIEW", "WISH");
    }

    @Test
    void 스트립_프레임은_사람마다_최근_6개까지다() throws Exception {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        User followee = user("followee", PrivacySetting.PUBLIC);
        follow(viewer, followee, base.minusDays(10));
        for (int i = 0; i < 8; i++) {
            wish(followee, base.plusMinutes(i));
        }

        JsonNode frames = getJson("/api/feed/strips", viewer.getId()).get("content").get(0).get("frames");

        assertThat(frames).hasSize(6);
        assertThat(LocalDateTime.parse(frames.get(0).get("occurredAt").asString())).isEqualTo(base.plusMinutes(7)); // toString() 비교 금지 — 위와 같은 이유
    }

    @Test
    void 조회_뒤_사라진_원천_행은_예외_없이_건너뛴다() {
        User viewer = user("viewer", PrivacySetting.PUBLIC);
        em.flush();
        ActivityRow ghost = new ActivityRow(viewer.getId(), ActivityType.WISH, 987_654_321L, base, null);
        ActivityRow ghostGroup = new ActivityRow(viewer.getId(), ActivityType.COLLECTION_MOVIES_ADDED, 987_654_322L, base,
                LocalDate.now());

        Map<ActivityRow, ?> assembled = activityAssembler.assemble(viewer.getId(), List.of(ghost, ghostGroup));

        assertThat(assembled).isEmpty();
    }
}
