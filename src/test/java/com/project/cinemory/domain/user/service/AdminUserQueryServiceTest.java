package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.dto.AdminUserDetailResponse;
import com.project.cinemory.domain.user.dto.AdminUserSort;
import com.project.cinemory.domain.user.dto.AdminUserSummaryResponse;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.repository.UserSocialAccountRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 회원 조회(admin-spec 3-2·3-3, 테스트 7절). 검색어는 실 DB에 이미 있는 행과 겹치지 않게 고유 토큰을 쓴다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class AdminUserQueryServiceTest {

    @Autowired
    private AdminUserQueryService service;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSocialAccountRepository socialAccountRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private WishMovieRepository wishMovieRepository;
    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private EntityManagerFactory emf;

    private User alpha;   // 이메일에 토큰
    private User beta;    // 닉네임에 토큰, PRIVATE, 소셜 2개
    private User gamma;   // 토큰 없음

    @BeforeEach
    void setUp() {
        alpha = userRepository.save(User.createLocal("zq9alpha@test.com", "{noop}pw", "알파"));
        // "PRIVATE도 관리자 목록·상세에 나온다"를 확인하는 테스트의 전제 — 가입 기본값(FRIENDS, V36)에 기대지 않고 명시한다
        User betaUser = User.createLocal("beta-admin-test@test.com", "{noop}pw", "zq9베타");
        betaUser.changePrivacySetting(PrivacySetting.PRIVATE);
        beta = userRepository.save(betaUser);
        gamma = userRepository.save(User.createLocal("gamma-admin-test@test.com", "{noop}pw", "감마"));
        alpha.changePrivacySetting(PrivacySetting.PUBLIC);
        // beta·gamma는 기본값 PRIVATE
        socialAccountRepository.save(UserSocialAccount.link(beta, OAuthProvider.KAKAO, "admin-test-kakao-1"));
        socialAccountRepository.save(UserSocialAccount.link(beta, OAuthProvider.GOOGLE, "admin-test-google-1"));
    }

    @Test
    void q는_이메일과_닉네임_양쪽에_걸린다() {
        PageResponse<AdminUserSummaryResponse> result = service.getUsers("ZQ9", AdminUserSort.CREATED_AT_DESC, 0, 20);

        assertThat(result.content()).extracting(AdminUserSummaryResponse::userId)
                .containsExactlyInAnyOrder(alpha.getId(), beta.getId());
    }

    @Test
    void 공백_q는_무시되고_와일드카드는_리터럴로_취급된다() {
        long total = userRepository.count();

        assertThat(service.getUsers("   ", AdminUserSort.CREATED_AT_DESC, 0, 1).totalElements()).isEqualTo(total);
        assertThat(service.getUsers(null, AdminUserSort.CREATED_AT_DESC, 0, 1).totalElements()).isEqualTo(total);
        // '%'가 와일드카드면 전부 걸린다
        assertThat(service.getUsers("zq9%", AdminUserSort.CREATED_AT_DESC, 0, 20).content()).isEmpty();
        assertThat(service.getUsers("zq9_lpha", AdminUserSort.CREATED_AT_DESC, 0, 20).content()).isEmpty();
    }

    @Test
    void 소셜_둘이_연결된_사용자는_socialProviders에_둘_다_나오고_PRIVATE도_조회된다() {
        AdminUserSummaryResponse row = service.getUsers("zq9", AdminUserSort.CREATED_AT_DESC, 0, 20).content().stream()
                .filter(r -> r.userId().equals(beta.getId())).findFirst().orElseThrow();

        assertThat(beta.getPrivacySetting()).isEqualTo(PrivacySetting.PRIVATE);
        assertThat(row.socialProviders()).containsExactlyInAnyOrder(OAuthProvider.KAKAO, OAuthProvider.GOOGLE);
        assertThat(row.hasPassword()).isTrue();
        assertThat(row.role()).isEqualTo(RoleType.USER);

        AdminUserSummaryResponse noSocial = service.getUsers("zq9", AdminUserSort.CREATED_AT_DESC, 0, 20).content()
                .stream().filter(r -> r.userId().equals(alpha.getId())).findFirst().orElseThrow();
        assertThat(noSocial.socialProviders()).isEmpty();
    }

    @Test
    void 정렬_enum은_닉네임_오름차순과_가입순을_지원한다() {
        // 같은 트랜잭션에서 만든 행은 created_at이 같을 수 있어 id 보조키로 순서가 정해진다
        List<Long> asc = service.getUsers("admin-test", AdminUserSort.CREATED_AT_ASC, 0, 20).content().stream()
                .map(AdminUserSummaryResponse::userId).toList();
        List<Long> desc = service.getUsers("admin-test", AdminUserSort.CREATED_AT_DESC, 0, 20).content().stream()
                .map(AdminUserSummaryResponse::userId).toList();
        assertThat(asc).containsExactly(beta.getId(), gamma.getId());
        assertThat(desc).containsExactly(gamma.getId(), beta.getId());

        List<String> byNickname = service.getUsers("admin-test", AdminUserSort.NICKNAME_ASC, 0, 20).content().stream()
                .map(AdminUserSummaryResponse::nickname).toList();
        // utf8mb4_0900_ai_ci에서 라틴이 한글보다 앞선다
        assertThat(byNickname).containsExactly("zq9베타", "감마");
    }

    @Test
    void 상세의_카운트_8종은_대표와_전체_회차를_구분한다() {
        Movie movie1 = movieRepository.save(Movie.builder().tmdbId(98_101L).title("관리자 테스트 1").build());
        Movie movie2 = movieRepository.save(Movie.builder().tmdbId(98_102L).title("관리자 테스트 2").build());

        // movie1을 두 번(첫 기록이 대표), movie2를 한 번 → 회차 3 / 본 영화 2
        record(beta, movie1, LocalDate.of(2026, 1, 1));
        record(beta, movie1, LocalDate.of(2026, 5, 1));
        record(beta, movie2, null);
        reviewRepository.save(Review.of(beta, movie1, "리뷰"));
        wishMovieRepository.save(WishMovie.of(beta, movie2));
        collectionRepository.save(Collection.builder().user(beta).name("c1").position(0).build());
        collectionRepository.save(Collection.builder().user(beta).name("c2").position(1).build());
        followRepository.save(Follow.of(alpha, beta));
        followRepository.save(Follow.of(gamma, beta));
        followRepository.save(Follow.of(beta, alpha));

        AdminUserDetailResponse detail = service.getUser(beta.getId());

        assertThat(detail.activity().watchRecordCount()).isEqualTo(3);
        assertThat(detail.activity().watchedMovieCount()).isEqualTo(2);
        assertThat(detail.activity().reviewCount()).isEqualTo(1);
        assertThat(detail.activity().wishCount()).isEqualTo(1);
        assertThat(detail.activity().collectionCount()).isEqualTo(2);
        assertThat(detail.activity().followerCount()).isEqualTo(2);
        assertThat(detail.activity().followingCount()).isEqualTo(1);
        assertThat(detail.activity().lastWatchDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(detail.privacySetting()).isEqualTo(PrivacySetting.PRIVATE);
        assertThat(detail.socialProviders()).containsExactlyInAnyOrder(OAuthProvider.KAKAO, OAuthProvider.GOOGLE);
    }

    @Test
    void 활동이_없으면_카운트는_0이고_lastWatchDate는_null이다() {
        AdminUserDetailResponse.Activity activity = service.getUser(gamma.getId()).activity();

        assertThat(activity.watchRecordCount()).isZero();
        assertThat(activity.lastWatchDate()).isNull();
    }

    @Test
    void 없는_회원은_USER_NOT_FOUND다() {
        assertThatThrownBy(() -> service.getUser(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        assertThatThrownBy(() -> service.getMe(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void 목록_한_페이지는_쿼리_3회다_목록_count_소셜() {
        // 첫 페이지 content가 size보다 작으면 Spring Data가 count를 생략하므로 size를 총 행 수보다 작게 잡는다(최소 3명 존재)
        em.flush();
        em.clear();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        PageResponse<AdminUserSummaryResponse> page = service.getUsers(null, AdminUserSort.CREATED_AT_DESC, 0, 2);

        assertThat(page.content()).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    private void record(User user, Movie movie, LocalDate watchDate) {
        watchRecordService.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(movie.getId(), watchDate, null, null, null, null, null));
    }
}
