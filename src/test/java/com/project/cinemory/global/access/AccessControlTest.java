package com.project.cinemory.global.access;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5-7 C-3 — {@code UserAccessPolicy} 호출부 9개 + FRIENDS 맞팔 판정 + {@code getMovieReviews}의
 * 예외 케이스({@code controller-layer-spec.md} 5-7-C 재개정판 기준).
 *
 * <p>C-2에서 빠진 4개 도메인(Collection·Comment·WatchRecord·Wish)을 목록에서 지우지 않고
 * 여기서 다룬다 — 4-6-E 소급 작업의 산출물 전부이며, 비공개 계정의 콘텐츠가 남에게 보이는지를
 * 검증하는 유일한 지점이다.
 *
 * <p>실행 방식은 {@link ViewerFlagTest}와 동일 — 실제 팔로우/댓글/리뷰 행이 필요해
 * {@code @Transactional} 롤백이 적용되는 MockMvc(슬라이스 아님)를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccessControlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User createUser(String email, String nickname, PrivacySetting privacy) {
        User user = User.createLocal(email, "{noop}password", nickname);
        user.changePrivacySetting(privacy);
        return userRepository.save(user);
    }

    private String tokenFor(User user) {
        return jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
    }

    private ResultActions callAnonymous(String path, Object... uriVars) throws Exception {
        return mockMvc.perform(get(path, uriVars));
    }

    private void assertAccessDenied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ── 1~9. PRIVATE → 403 (9개 호출부) ─────────────────────────────────────────

    @Test
    void 컬렉션_목록_비공개면_403이다() throws Exception {
        User target = createUser("ac-collections@test.com", "비공개유저1", PrivacySetting.PRIVATE);
        assertAccessDenied(callAnonymous("/api/users/{userId}/collections", target.getId()));
    }

    @Test
    void 컬렉션_영화목록_비공개면_403이다() throws Exception {
        User target = createUser("ac-collection-movies@test.com", "비공개유저2", PrivacySetting.PRIVATE);
        Collection collection = collectionRepository.save(Collection.of(target, "비공개 컬렉션", null));
        assertAccessDenied(callAnonymous("/api/collections/{collectionId}/movies", collection.getId()));
    }

    @Test
    void 댓글_작성_비공개_대상이면_403이다() throws Exception {
        User owner = createUser("ac-comment-create-owner@test.com", "비공개유저3", PrivacySetting.PRIVATE);
        Collection collection = collectionRepository.save(Collection.of(owner, "비공개 컬렉션", null));
        User author = createUser("ac-comment-create-author@test.com", "댓글시도자", PrivacySetting.PUBLIC);

        String body = """
                {"targetType":"COLLECTION","targetId":%d,"content":"댓글 시도"}
                """.formatted(collection.getId());

        assertAccessDenied(mockMvc.perform(post("/api/comments")
                .header("Authorization", "Bearer " + tokenFor(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)));
    }

    @Test
    void 댓글_목록_비공개_대상이면_403이다() throws Exception {
        User owner = createUser("ac-comment-list-owner@test.com", "비공개유저4", PrivacySetting.PRIVATE);
        Collection collection = collectionRepository.save(Collection.of(owner, "비공개 컬렉션", null));

        assertAccessDenied(mockMvc.perform(get("/api/comments")
                .param("targetType", "COLLECTION")
                .param("targetId", collection.getId().toString())));
    }

    @Test
    void 팔로워_목록_비공개면_403이다() throws Exception {
        User target = createUser("ac-followers@test.com", "비공개유저5", PrivacySetting.PRIVATE);
        assertAccessDenied(callAnonymous("/api/users/{userId}/followers", target.getId()));
    }

    @Test
    void 팔로잉_목록_비공개면_403이다() throws Exception {
        User target = createUser("ac-followings@test.com", "비공개유저6", PrivacySetting.PRIVATE);
        assertAccessDenied(callAnonymous("/api/users/{userId}/followings", target.getId()));
    }

    @Test
    void 시청기록_목록_비공개면_403이다() throws Exception {
        User target = createUser("ac-records@test.com", "비공개유저7", PrivacySetting.PRIVATE);
        assertAccessDenied(callAnonymous("/api/users/{userId}/records", target.getId()));
    }

    /** 접근 판정이 movieId 존재 여부보다 먼저 실행되므로 가짜 movieId로도 403을 확인할 수 있다. */
    @Test
    void 시청기록_회차조회_비공개면_403이다() throws Exception {
        User target = createUser("ac-watchlog@test.com", "비공개유저8", PrivacySetting.PRIVATE);
        assertAccessDenied(callAnonymous("/api/users/{userId}/records/movies/{movieId}", target.getId(), 1L));
    }

    @Test
    void 위시리스트_비공개면_403이다() throws Exception {
        User target = createUser("ac-wishes@test.com", "비공개유저9", PrivacySetting.PRIVATE);
        assertAccessDenied(callAnonymous("/api/users/{userId}/wishes", target.getId()));
    }

    // ── 10~11. FRIENDS = 상호 팔로우 (대표 엔드포인트: 컬렉션 목록) ──────────────────

    @Test
    void FRIENDS_단방향_팔로우는_403이다() throws Exception {
        User target = createUser("ac-friends-oneway-target@test.com", "친구공개유저1", PrivacySetting.FRIENDS);
        User viewer = createUser("ac-friends-oneway-viewer@test.com", "짝사랑뷰어", PrivacySetting.PUBLIC);
        followRepository.save(Follow.of(viewer, target)); // viewer -> target 단방향

        assertAccessDenied(mockMvc.perform(get("/api/users/{userId}/collections", target.getId())
                .header("Authorization", "Bearer " + tokenFor(viewer))));
    }

    @Test
    void FRIENDS_맞팔이면_200이다() throws Exception {
        User target = createUser("ac-friends-mutual-target@test.com", "친구공개유저2", PrivacySetting.FRIENDS);
        User viewer = createUser("ac-friends-mutual-viewer@test.com", "맞팔뷰어", PrivacySetting.PUBLIC);
        followRepository.save(Follow.of(viewer, target));
        followRepository.save(Follow.of(target, viewer)); // 양방향 = 맞팔

        mockMvc.perform(get("/api/users/{userId}/collections", target.getId())
                        .header("Authorization", "Bearer " + tokenFor(viewer)))
                .andExpect(status().isOk());
    }

    // ── 12. PUBLIC은 비로그인도 200 (대표 엔드포인트: 시청기록 목록) ─────────────────

    @Test
    void PUBLIC_시청기록_목록은_비로그인도_200이다() throws Exception {
        User target = createUser("ac-public-records@test.com", "전체공개유저", PrivacySetting.PUBLIC);
        callAnonymous("/api/users/{userId}/records", target.getId())
                .andExpect(status().isOk());
    }

    // ── 13. getMovieReviews — 403이 아니라 목록에서 조용히 빠진다 ────────────────────

    @Test
    void 리뷰_목록은_비공개_작성자_리뷰만_조용히_제외한다() throws Exception {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(9_999_999L).title("접근제어 테스트 영화").build());
        User privateAuthor = createUser("ac-review-author@test.com", "비공개리뷰어", PrivacySetting.PRIVATE);
        reviewRepository.save(Review.of(privateAuthor, movie, 7.5, "비공개 유저의 리뷰"));

        mockMvc.perform(get("/api/movies/{movieId}/reviews", movie.getId()))
                .andExpect(status().isOk()) // ACCESS_DENIED가 아니다 — 대상 자체는 공개 리소스
                .andExpect(jsonPath("$.content").isEmpty());
    }
}
