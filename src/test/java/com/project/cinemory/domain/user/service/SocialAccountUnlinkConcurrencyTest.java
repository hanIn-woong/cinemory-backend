package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.repository.UserSocialAccountRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 해제 경합 — "인증 수단 최소 1개"가 락으로 실제로 지켜지는지 고정한다 (account-integrity D-5-D, D-4 #3 이월).
 *
 * <p>비밀번호 없는 사용자가 카카오·구글을 가진 상태에서 두 기기가 <b>동시에 서로 다른 제공자</b>를 해제한다.
 * 락이 없으면 두 트랜잭션이 각자 "2개 남음"을 보고 둘 다 지워 <b>인증 수단 0개(로그인 불가 계정)</b>가 된다.
 * 락이 있으면 뒤의 트랜잭션은 앞의 커밋을 기다렸다가 잠금 읽기로 1개를 보고 {@code LAST_AUTH_METHOD}로 거부된다.
 *
 * <p><b>⚠️ 이 클래스에 {@code @Transactional}을 붙이지 말 것.</b> 각 스레드가 자기 트랜잭션을 <b>커밋</b>해야 락 경합이 생긴다.
 * 롤백형으로 바꾸면 경합 자체가 사라져 락을 지워도 <b>항상 통과</b>한다. 그래서 데이터는 직접 넣고 {@link #cleanUp}에서 지운다
 * ({@code user_social_account}는 FK {@code ON DELETE CASCADE}라 사용자만 지우면 된다).
 */
@SpringBootTest
class SocialAccountUnlinkConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private SocialAccountService socialAccountService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSocialAccountRepository userSocialAccountRepository;

    private Long userId;

    @BeforeEach
    void seedSocialOnlyUserWithTwoProviders() {
        // 반복마다 다른 값 — 앞 반복의 정리가 실패해도 UNIQUE에 걸려 원인이 가려지지 않게
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.createOAuth("race-" + unique + "@test.com", "경합유저", null));
        userSocialAccountRepository.save(UserSocialAccount.link(user, OAuthProvider.KAKAO, "kakao-" + unique));
        userSocialAccountRepository.save(UserSocialAccount.link(user, OAuthProvider.GOOGLE, "google-" + unique));
        userId = user.getId();
    }

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    @RepeatedTest(5)
    void 서로_다른_제공자를_동시에_해제하면_정확히_하나만_성공하고_연결_하나가_남는다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Outcome> kakao = executor.submit(unlinkAfter(ready, start, "kakao"));
            Future<Outcome> google = executor.submit(unlinkAfter(ready, start, "google"));

            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = List.of(
                    kakao.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    google.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            long successes = outcomes.stream().filter(outcome -> outcome == Outcome.SUCCESS).count();
            long lastAuthMethod = outcomes.stream().filter(outcome -> outcome == Outcome.LAST_AUTH_METHOD).count();
            int remaining = userSocialAccountRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId).size();

            // 락이 빠졌을 때의 실패 형태는 "성공 2, 남은 연결 0"이다
            String summary = "결과=" + outcomes + ", 성공=" + successes + ", 남은 연결=" + remaining;
            assertThat(successes).as(summary).isEqualTo(1);
            assertThat(lastAuthMethod).as(summary).isEqualTo(1);
            assertThat(remaining).as(summary).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 두 스레드가 모두 준비된 뒤 같은 신호로 출발한다 — 한쪽이 먼저 끝나 경합 없이 지나가는 것을 줄인다. */
    private Callable<Outcome> unlinkAfter(CountDownLatch ready, CountDownLatch start, String provider) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                socialAccountService.unlink(userId, provider);
                return Outcome.SUCCESS;
            } catch (BusinessException e) {
                return e.getErrorCode() == ErrorCode.LAST_AUTH_METHOD ? Outcome.LAST_AUTH_METHOD : Outcome.OTHER_ERROR;
            }
        };
    }

    private enum Outcome { SUCCESS, LAST_AUTH_METHOD, OTHER_ERROR }
}
