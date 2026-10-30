package com.project.cinemory.domain.user.profileimage;

import com.project.cinemory.domain.comment.entity.Comment;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * S3 키가 응답에 그대로 새는지 검사한다 (account-integrity-spec B-3 · B-5-1).
 * 프로필 이미지를 내보내는 DTO 7종 — {@code UserResponse} · {@code UserProfileResponse} · {@code FollowUserResponse}(목록·검색) ·
 * {@code ReviewAuthorResponse} · {@code CommentAuthorResponse} · {@code FeedUserResponse}(스트립·팔로우 활동) · {@code AdminUserDetailResponse} —
 * 를 각각 만드는 호출부를 실제 엔드포인트로 돌려, 응답의 모든 {@code profileImage}가 완성된 URL인지 본다.
 * 호출부 하나가 변환을 빠뜨리면 그 엔드포인트의 검사가 실패한다. 빈 결과로 통과하지 않도록 엔드포인트마다 최소 개수도 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileImageKeyLeakTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "https://media.test/";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User author;   // 키를 가진 작성자 · 팔로이
    private User viewer;   // 키를 가진 조회자 · 팔로워
    private User admin;
    private Movie movie;
    private Review review;

    private User user(String tag, boolean withImage) {
        User user = User.createOAuth(tag + "-" + SEQ.incrementAndGet() + "@leak.test", tag, null);
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        em.persist(user);
        em.flush();
        if (withImage) {
            user.changeProfileImage("profile/" + user.getId() + "/" + SEQ.incrementAndGet() + ".jpg");
        }
        return user;
    }

    private void fixture() {
        author = user("작성자", true);
        viewer = user("조회자", true);
        admin = user("관리자", false);
        em.flush();
        jdbc.update("UPDATE `user` SET role = 'ADMIN' WHERE id = ?", admin.getId());
        movie = Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("키 노출 테스트").build();
        em.persist(movie);
        review = Review.of(author, movie, "리뷰");
        em.persist(review);
        em.persist(Comment.builder().user(author).targetType(TargetType.REVIEW).targetId(review.getId()).content("댓글").build());
        em.persist(Follow.of(viewer, author));
        em.persist(Follow.of(author, viewer));
        em.flush();
        em.clear();
    }

    private List<String> profileImages(String path, User as) throws Exception {
        var request = get(path);
        if (as != null) {
            request = request.header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(as.getId(),
                    as == admin ? RoleType.ADMIN : RoleType.USER));
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        List<String> found = new ArrayList<>();
        collect(JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)), found);
        return found;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getKey().equals("profileImage") && !e.getValue().isNull()) {
                    out.add(e.getValue().asString());
                } else {
                    collect(e.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }

    private void assertResolved(String path, User as, int atLeast) throws Exception {
        List<String> images = profileImages(path, as);
        assertThat(images).as(path + " — profileImage가 응답에 있어야 검사가 의미 있다").hasSizeGreaterThanOrEqualTo(atLeast);
        assertThat(images).as(path).allSatisfy(image -> assertThat(image).startsWith(BASE + "profile/"));
    }

    @Test
    void UserResponse() throws Exception {
        fixture();
        assertResolved("/api/users/me", author, 1);
    }

    @Test
    void UserProfileResponse() throws Exception {
        fixture();
        assertResolved("/api/users/" + author.getId() + "/profile", viewer, 1);
    }

    @Test
    void FollowUserResponse_팔로워_팔로잉_목록() throws Exception {
        fixture();
        assertResolved("/api/users/" + author.getId() + "/followers", viewer, 1);
        assertResolved("/api/users/" + author.getId() + "/followings", viewer, 1);
    }

    @Test
    void FollowUserResponse_검색() throws Exception {
        fixture();
        assertResolved("/api/users/search?q=" + "작성자", viewer, 1);
    }

    @Test
    void ReviewAuthorResponse() throws Exception {
        fixture();
        assertResolved("/api/movies/" + movie.getId() + "/reviews", viewer, 1);
    }

    @Test
    void CommentAuthorResponse() throws Exception {
        fixture();
        assertResolved("/api/comments?targetType=REVIEW&targetId=" + review.getId(), viewer, 1);
    }

    @Test
    void FeedUserResponse_스트립과_내_스트립() throws Exception {
        fixture();
        Movie other = Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("활동 영화").build();
        em.persist(other);
        em.persist(Review.of(em.find(User.class, author.getId()), other, "활동"));
        em.flush();
        // 팔로잉 스트립(ActivityAssembler 아닌 FeedService.buildStrips) + 내 스트립(FeedService.meStrip)
        assertResolved("/api/feed/strips", viewer, 2);
    }

    @Test
    void FeedUserResponse_팔로우_활동의_팔로이() throws Exception {
        fixture();
        // viewer → author 팔로우가 viewer의 활동으로 합성되고, followee가 ActivityAssembler.followResponse에서 만들어진다
        assertResolved("/api/users/" + viewer.getId() + "/activities", viewer, 1);
    }

    @Test
    void AdminUserDetailResponse() throws Exception {
        fixture();
        assertResolved("/api/admin/users/" + author.getId(), admin, 1);
    }
}
