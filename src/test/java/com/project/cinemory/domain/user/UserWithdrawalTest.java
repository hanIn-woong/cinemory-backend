package com.project.cinemory.domain.user;

import com.project.cinemory.domain.auth.dto.LoginRequest;
import com.project.cinemory.domain.auth.dto.TokenResponse;
import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.service.AuthService;
import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.comment.entity.Comment;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.recommendation.service.RecommendationCache;
import com.project.cinemory.domain.recommendation.service.RecommendationCache.Entry;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.event.UserWithdrawnEvent;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 탈퇴 (account-integrity-spec C-2). 실 DB({@code cinemory_test})의 CASCADE를 그대로 쓴다 —
 * 탈퇴 흐름은 자식 엔티티를 로드하지 않으므로, 테스트도 요청 직전에 flush+clear로 같은 상태를 만든다.
 * 기본은 롤백되는 트랜잭션이고, 커밋 후 동작(추천 캐시)을 보는 테스트만 {@code NOT_SUPPORTED}로 직접 트랜잭션을 연다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserWithdrawalTest {

    private static final String PASSWORD = "Passw0rd!x1";
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private AuthService authService;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private RecommendationCache cache;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User localUser(String tag) {
        String email = tag + "-" + SEQ.incrementAndGet() + "@withdraw.test";
        userService.signUpLocal(new SignUpLocalRequest(email, PASSWORD, tag));
        return em.createQuery("select u from User u where u.email = :e", User.class)
                .setParameter("e", email).getSingleResult();
    }

    private User socialOnlyUser(String tag) {
        User user = User.createOAuth(tag + "-" + SEQ.incrementAndGet() + "@withdraw.test", tag, null);
        em.persist(user);
        em.persist(UserSocialAccount.link(user, OAuthProvider.KAKAO, "kakao-" + SEQ.incrementAndGet()));
        return user;
    }

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L)
                .title("탈퇴 테스트 영화").build();
        em.persist(movie);
        return movie;
    }

    private Collection collectionWithMovie(User owner, Movie movie) {
        Collection collection = Collection.builder().user(owner).name("내 컬렉션").position(0).build();
        em.persist(collection);
        em.persist(CollectionMovie.of(collection, movie, 0));
        return collection;
    }

    private Review review(User author, Movie movie) {
        Review review = Review.of(author, movie, "리뷰");
        em.persist(review);
        return review;
    }

    private void comment(User author, TargetType type, Long targetId) {
        em.persist(Comment.builder().user(author).targetType(type).targetId(targetId).content("댓글").build());
    }

    /** 요청 직전 상태: 영속성 컨텍스트에 자식 엔티티가 없어야 실제 요청과 같다. */
    private void detach() {
        em.flush();
        em.clear();
    }

    private long count(String sql, Object... args) {
        em.flush();
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private MockHttpServletRequestBuilder withdrawal(Long userId, String body) {
        MockHttpServletRequestBuilder request = post("/api/users/me/withdrawal")
                .header("Authorization", "Bearer " + accessToken(userId));
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String accessToken(Long userId) {
        User user = em.find(User.class, userId);
        return jwtTokenProvider.createAccessToken(userId, user == null ? RoleType.USER : user.getRole());
    }

    private long userRows(Long userId) {
        return count("SELECT COUNT(*) FROM `user` WHERE id = ?", userId);
    }

    // ---- 삭제 범위 ----------------------------------------------------------------------------------

    @Test
    void 로컬_사용자_탈퇴는_관련_행을_전부_지운다() throws Exception {
        User me = localUser("me");
        User other = localUser("other");
        Movie movie = movie();
        Collection collection = collectionWithMovie(me, movie);
        review(me, movie);
        em.persist(WatchRecord.builder().user(me).movie(movie).watchDate(LocalDate.of(2026, 1, 1))
                .representative(true).watchType(WatchType.ETC).rating(BigDecimal.valueOf(8)).build());
        em.persist(WishMovie.of(me, movie));
        em.persist(Follow.of(me, other));
        em.persist(Follow.of(other, me));
        comment(me, TargetType.REVIEW, review(other, movie).getId());
        TokenResponse tokens = authService.login(new LoginRequest(me.getEmail(), PASSWORD));
        assertThat(tokens.refreshToken()).isNotBlank();
        Long id = me.getId();
        Long collectionId = collection.getId();
        detach();

        mockMvc.perform(withdrawal(id, "{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isNoContent());

        assertThat(userRows(id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM collection WHERE user_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM collection_movie WHERE collection_id = ?", collectionId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM review WHERE user_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM watch_record WHERE user_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM wish_movie WHERE user_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM follow WHERE follower_id = ? OR following_id = ?", id, id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM comment WHERE user_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id = ?", id)).isZero();
        assertThat(userRows(other.getId())).isEqualTo(1);
    }

    @Test
    void 탈퇴자의_컬렉션과_리뷰에_남이_단_댓글은_지워지고_제3자_글의_댓글과_나머지_팔로우는_남는다() throws Exception {
        User me = localUser("me");
        User other = localUser("other");
        User third = localUser("third");
        Movie movie = movie();
        Collection myCollection = collectionWithMovie(me, movie);
        Review myReview = review(me, movie);
        Collection thirdCollection = collectionWithMovie(third, movie);
        Review thirdReview = review(third, movie);
        comment(other, TargetType.COLLECTION, myCollection.getId());
        comment(other, TargetType.REVIEW, myReview.getId());
        comment(other, TargetType.COLLECTION, thirdCollection.getId());
        comment(other, TargetType.REVIEW, thirdReview.getId());
        em.persist(Follow.of(other, me));
        em.persist(Follow.of(other, third));
        detach();

        mockMvc.perform(withdrawal(me.getId(), "{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM comment WHERE target_type = 'COLLECTION' AND target_id = ?",
                myCollection.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM comment WHERE target_type = 'REVIEW' AND target_id = ?",
                myReview.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM comment WHERE target_type = 'COLLECTION' AND target_id = ?",
                thirdCollection.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM comment WHERE target_type = 'REVIEW' AND target_id = ?",
                thirdReview.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM follow WHERE follower_id = ? AND following_id = ?",
                other.getId(), third.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM follow WHERE following_id = ?", me.getId())).isZero();
    }

    // ---- 본인 확인 ----------------------------------------------------------------------------------

    @Test
    void 잘못된_비밀번호는_INVALID_CREDENTIALS이고_아무것도_지우지_않는다() throws Exception {
        User me = localUser("me");
        Movie movie = movie();
        collectionWithMovie(me, movie);
        review(me, movie);
        detach();

        mockMvc.perform(withdrawal(me.getId(), "{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        assertThat(userRows(me.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM collection WHERE user_id = ?", me.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM review WHERE user_id = ?", me.getId())).isEqualTo(1);
    }

    @Test
    void 비밀번호가_있는_계정이_비밀번호를_생략해도_INVALID_CREDENTIALS다() throws Exception {
        User me = localUser("me");
        detach();

        mockMvc.perform(withdrawal(me.getId(), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        assertThat(userRows(me.getId())).isEqualTo(1);
    }

    @Test
    void 소셜_전용_사용자는_비밀번호_없이_탈퇴하고_연결도_지워진다() throws Exception {
        User me = socialOnlyUser("social");
        detach();

        mockMvc.perform(withdrawal(me.getId(), null)).andExpect(status().isNoContent());

        assertThat(userRows(me.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_social_account WHERE user_id = ?", me.getId())).isZero();
    }

    @Test
    void 소셜을_연결한_로컬_사용자는_비밀번호가_필요하고_탈퇴하면_연결이_지워진다() throws Exception {
        User me = localUser("both");
        em.persist(UserSocialAccount.link(me, OAuthProvider.KAKAO, "kakao-" + SEQ.incrementAndGet()));
        detach();

        mockMvc.perform(withdrawal(me.getId(), null)).andExpect(status().isUnauthorized());
        assertThat(count("SELECT COUNT(*) FROM user_social_account WHERE user_id = ?", me.getId())).isEqualTo(1);

        mockMvc.perform(withdrawal(me.getId(), "{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(userRows(me.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_social_account WHERE user_id = ?", me.getId())).isZero();
    }

    @Test
    void ADMIN은_403이고_아무것도_지우지_않는다() throws Exception {
        User admin = localUser("rootuser");
        Movie movie = movie();
        collectionWithMovie(admin, movie);
        detach();
        jdbc.update("UPDATE `user` SET role = 'ADMIN' WHERE id = ?", admin.getId());

        mockMvc.perform(withdrawal(admin.getId(), "{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_WITHDRAWAL_FORBIDDEN"));

        assertThat(userRows(admin.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM collection WHERE user_id = ?", admin.getId())).isEqualTo(1);
    }

    // ---- 재가입·남은 토큰 ---------------------------------------------------------------------------

    @Test
    void 탈퇴_후_같은_이메일로_재가입할_수_있다() throws Exception {
        User me = localUser("again");
        String email = me.getEmail();
        detach();

        mockMvc.perform(withdrawal(me.getId(), "{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        userService.signUpLocal(new SignUpLocalRequest(email, PASSWORD, "again"));
        assertThat(count("SELECT COUNT(*) FROM `user` WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void 탈퇴한_사용자의_Access_Token은_me_조회가_404이고_리프레시_재발급은_401이다() throws Exception {
        User me = localUser("stale");
        TokenResponse tokens = authService.login(new LoginRequest(me.getEmail(), PASSWORD));
        detach();

        mockMvc.perform(withdrawal(me.getId(), "{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        // 실제 요청은 커밋이 DELETE를 내보낸다 — 한 트랜잭션으로 도는 테스트는 직접 flush해 같은 상태를 만든다
        detach();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(post("/api/auth/reissue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokens.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 추천 캐시 (커밋 후) ------------------------------------------------------------------------

    private static final Entry EMPTY = new Entry(List.of(), false, List.of());

    private void seedCache() {
        for (String key : List.of("FY:1", "Q:1:범죄", "Q:1:로맨스", "FY:12", "Q:12:범죄", "FY:2", "Q:2:범죄")) {
            cache.putResult(key, EMPTY);
        }
    }

    private void clearCache() {
        for (String key : List.of("FY:1", "Q:1:범죄", "Q:1:로맨스", "FY:12", "Q:12:범죄", "FY:2", "Q:2:범죄")) {
            cache.evictUser(Long.parseLong(key.split(":")[1]));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 커밋_후_해당_사용자의_추천_캐시만_지워진다() {
        seedCache();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(s -> eventPublisher.publishEvent(new UserWithdrawnEvent(1L)));

            assertThat(cache.getResult("FY:1")).isNull();
            assertThat(cache.getResult("Q:1:범죄")).isNull();
            assertThat(cache.getResult("Q:1:로맨스")).isNull();
            // Q:1 접두가 Q:12…를 지우면 안 된다 — 접두는 콜론까지
            assertThat(cache.getResult("FY:12")).isNotNull();
            assertThat(cache.getResult("Q:12:범죄")).isNotNull();
            assertThat(cache.getResult("FY:2")).isNotNull();
            assertThat(cache.getResult("Q:2:범죄")).isNotNull();
        } finally {
            clearCache();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 롤백되면_추천_캐시를_지우지_않는다() {
        seedCache();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                eventPublisher.publishEvent(new UserWithdrawnEvent(1L));
                s.setRollbackOnly();
            });

            assertThat(cache.getResult("FY:1")).isNotNull();
            assertThat(cache.getResult("Q:1:범죄")).isNotNull();
        } finally {
            clearCache();
        }
    }
}
