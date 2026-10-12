package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.repository.UserSocialAccountRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V24 연결 구조 이후의 {@code UserService} 인증 경로 — 소셜 로그인 분기(D-2-A)와 "비밀번호 = 로컬 가입자만"(S-6).
 * 실 DB({@code cinemory_test})에서 본다 — {@code user} + {@code user_social_account} 두 테이블이 함께 움직여야 해서다.
 */
@SpringBootTest
@Transactional
class UserServiceSocialAuthTest {

    private static final String KAKAO_ID = "3000000001";
    private static final String RAW_PASSWORD = "password1234";

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSocialAccountRepository userSocialAccountRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User localUserWithKakao(String email) {
        User user = userRepository.save(User.createLocal(email, passwordEncoder.encode(RAW_PASSWORD), "로컬유저"));
        userSocialAccountRepository.save(UserSocialAccount.link(user, OAuthProvider.KAKAO, KAKAO_ID));
        return user;
    }

    private List<UserSocialAccount> linkedAccounts(Long userId) {
        return userSocialAccountRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId);
    }

    // ------------------------------------------------------------ 소셜 로그인 분기

    @Test
    void 처음_보는_소셜_계정이면_user와_user_social_account를_함께_만든다() {
        User user = userService.signUpOAuth("new@kakao.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID);

        assertThat(user.getId()).isNotNull();
        assertThat(user.hasPassword()).isFalse();
        assertThat(userSocialAccountRepository.findUserByProviderAndProviderId(OAuthProvider.KAKAO, KAKAO_ID))
                .hasValueSatisfying(owner -> assertThat(owner.getId()).isEqualTo(user.getId()));
    }

    @Test
    void 연결된_소셜_계정이면_그_사용자로_로그인한다_멱등() {
        User first = userService.signUpOAuth("new@kakao.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID);

        User second = userService.signUpOAuth("new@kakao.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(linkedAccounts(first.getId())).hasSize(1);
    }

    /** 로컬 가입자가 연결해 둔 카카오로 들어오면, 카카오 이메일이 달라도 그 로컬 계정으로 로그인된다(S-5). */
    @Test
    void 로컬_가입자가_연결한_소셜로_로그인하면_그_로컬_계정이다() {
        User local = localUserWithKakao("local@test.com");

        User loggedIn = userService.signUpOAuth("different@kakao.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID);

        assertThat(loggedIn.getId()).isEqualTo(local.getId());
    }

    /** 자동 연결하지 않는다 — 이메일 기준 자동 연결은 선점형 계정 탈취 경로다(S-5). */
    @Test
    void 연결은_없는데_이메일이_이미_있으면_409_EMAIL_ALREADY_REGISTERED이고_자동_연결하지_않는다() {
        User local = userRepository.save(User.createLocal("taken@test.com", "$2a$10$hash", "로컬유저"));

        assertThatThrownBy(() ->
                userService.signUpOAuth("taken@test.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
        assertThat(linkedAccounts(local.getId())).isEmpty();
    }

    // ------------------------------------------------- 비밀번호 = 로컬 가입자만 (S-6)

    /** V24 이전의 {@code isOAuthUser()}였다면 소셜을 연결한 순간 로컬 로그인이 막혔을 경우다. */
    @Test
    void 소셜을_연결한_로컬_가입자도_비밀번호로_로그인한다() {
        User local = localUserWithKakao("local@test.com");

        User loggedIn = userService.login("local@test.com", RAW_PASSWORD);

        assertThat(loggedIn.getId()).isEqualTo(local.getId());
    }

    @Test
    void 소셜을_연결한_로컬_가입자도_비밀번호를_변경한다() {
        User local = localUserWithKakao("local@test.com");

        userService.changePassword(local.getId(), RAW_PASSWORD, "newPassword5678");

        assertThat(passwordEncoder.matches("newPassword5678", local.getPasswordHash())).isTrue();
    }

    @Test
    void 소셜_전용_사용자는_비밀번호를_변경할_수_없다_INVALID_AUTH_METHOD() {
        User kakaoOnly = userService.signUpOAuth("new@kakao.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID);

        assertThatThrownBy(() -> userService.changePassword(kakaoOnly.getId(), "anything", "newPassword5678"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_AUTH_METHOD);
    }

    @Test
    void 소셜_전용_사용자의_로컬_로그인_시도는_INVALID_CREDENTIALS() {
        userService.signUpOAuth("new@kakao.com", "카카오유저", null, OAuthProvider.KAKAO, KAKAO_ID);

        assertThatThrownBy(() -> userService.login("new@kakao.com", "anything"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }
}
