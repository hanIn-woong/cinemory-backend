package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.service.OAuthVerificationService;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.domain.user.dto.SocialAccountsResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.repository.UserSocialAccountRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 소셜 계정 연결·조회·해제 (account-integrity S-5·S-6, D-2-A) — 실 DB({@code cinemory_test}, V24)에서 본다.
 *
 * <p>검증 관문({@code OAuthVerificationService})만 가짜로 둔다 — 그 순서는 {@code OAuthVerificationServiceTest}가 지킨다.
 * 여기서는 관문을 통과한 뒤의 <b>판정 순서와 불변식</b>을 본다.
 *
 * <p>제공자가 둘(카카오·구글)이 되면서 "소셜 전용 사용자가 소셜 2개 중 하나를 해제"를 여기서 본다(account-integrity D-5-D).
 * 동시 해제 경합(비관적 락)은 이 클래스가 아니라 {@code SocialAccountUnlinkConcurrencyTest}가 본다 — 이 클래스는
 * {@code @Transactional}(롤백형)이라 두 호출이 한 트랜잭션에 들어가 경합 자체가 생기지 않는다.
 */
@SpringBootTest
@Transactional
class SocialAccountServiceTest {

    private static final String KAKAO_ID = "3000000001";
    private static final String GOOGLE_ID = "109876543210987654321";
    private static final String GOOGLE_ID_TOKEN = "google.id.token";
    private static final String ID_TOKEN = "kakao.id.token";
    private static final String NONCE = "nonce-abc";

    @Autowired
    private SocialAccountService socialAccountService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSocialAccountRepository userSocialAccountRepository;

    @MockitoBean
    private OAuthVerificationService oauthVerificationService;

    private User localUser(String email) {
        return userRepository.save(User.createLocal(email, "$2a$10$hash", "로컬유저"));
    }

    private User kakaoOnlyUser(String email, String kakaoId) {
        User user = userRepository.save(User.createOAuth(email, "카카오유저", null));
        userSocialAccountRepository.save(UserSocialAccount.link(user, OAuthProvider.KAKAO, kakaoId));
        return user;
    }

    /** 검증 관문이 돌려줄 카카오 사용자. 이메일은 연결 대상 사용자와 달라도 된다(S-5). */
    private void givenVerifiedKakao(String kakaoId) {
        given(oauthVerificationService.verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE))
                .willReturn(new OAuthUserInfo(kakaoId, "other@kakao.com", "카카오유저", null));
    }

    private void givenVerifiedGoogle(String googleId) {
        given(oauthVerificationService.verify(OAuthProvider.GOOGLE, GOOGLE_ID_TOKEN, NONCE))
                .willReturn(new OAuthUserInfo(googleId, "other@gmail.com", "구글유저", null));
    }

    /** 비밀번호 없이 카카오·구글 둘만 가진 사용자. */
    private User kakaoAndGoogleUser(String email) {
        User user = kakaoOnlyUser(email, KAKAO_ID);
        userSocialAccountRepository.save(UserSocialAccount.link(user, OAuthProvider.GOOGLE, GOOGLE_ID));
        return user;
    }

    private List<UserSocialAccount> linkedAccounts(Long userId) {
        return userSocialAccountRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId);
    }

    // ------------------------------------------------------------------ 연결

    @Test
    void 로컬_가입자가_카카오를_연결하면_연결이_저장된다_이메일이_달라도_된다() {
        User user = localUser("local@test.com");
        givenVerifiedKakao(KAKAO_ID);

        socialAccountService.link(user.getId(), "kakao", ID_TOKEN, NONCE);

        assertThat(userSocialAccountRepository.findUserByProviderAndProviderId(OAuthProvider.KAKAO, KAKAO_ID))
                .hasValueSatisfying(owner -> assertThat(owner.getId()).isEqualTo(user.getId()));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo("local@test.com");
    }

    @Test
    void 카카오_가입자가_구글을_연결하면_두_제공자가_모두_연결된다() {
        User me = kakaoOnlyUser("me@test.com", KAKAO_ID);
        givenVerifiedGoogle(GOOGLE_ID);

        socialAccountService.link(me.getId(), "google", GOOGLE_ID_TOKEN, NONCE);

        assertThat(linkedAccounts(me.getId()))
                .extracting(UserSocialAccount::getProvider)
                .containsExactly(OAuthProvider.KAKAO, OAuthProvider.GOOGLE);
        assertThat(userSocialAccountRepository.findUserByProviderAndProviderId(OAuthProvider.GOOGLE, GOOGLE_ID))
                .hasValueSatisfying(owner -> assertThat(owner.getId()).isEqualTo(me.getId()));
    }

    @Test
    void 다른_사용자에게_연결된_구글_계정은_409_SOCIAL_ACCOUNT_ALREADY_LINKED() {
        kakaoAndGoogleUser("owner@test.com");
        User me = localUser("me@test.com");
        givenVerifiedGoogle(GOOGLE_ID);

        assertThatThrownBy(() -> socialAccountService.link(me.getId(), "google", GOOGLE_ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        assertThat(linkedAccounts(me.getId())).isEmpty();
    }

    @Test
    void 다른_사용자에게_연결된_소셜_계정은_409_SOCIAL_ACCOUNT_ALREADY_LINKED() {
        kakaoOnlyUser("owner@test.com", KAKAO_ID);
        User me = localUser("me@test.com");
        givenVerifiedKakao(KAKAO_ID);

        assertThatThrownBy(() -> socialAccountService.link(me.getId(), "kakao", ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        assertThat(linkedAccounts(me.getId())).isEmpty();
    }

    @Test
    void 내게_같은_제공자가_이미_있으면_409_SOCIAL_PROVIDER_ALREADY_LINKED() {
        User me = kakaoOnlyUser("me@test.com", KAKAO_ID);
        givenVerifiedKakao("3000000999"); // 다른 카카오 계정

        assertThatThrownBy(() -> socialAccountService.link(me.getId(), "kakao", ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
    }

    @Test
    void 이미_내게_연결된_바로_그_소셜_계정을_다시_연결해도_SOCIAL_PROVIDER_ALREADY_LINKED() {
        User me = kakaoOnlyUser("me@test.com", KAKAO_ID);
        givenVerifiedKakao(KAKAO_ID);

        assertThatThrownBy(() -> socialAccountService.link(me.getId(), "kakao", ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
    }

    /** 검증에 실패한 토큰으로는 아무것도 연결되지 않는다 — 여기가 뚫리면 남의 소셜 계정을 내 계정에 붙일 수 있다. */
    @Test
    void 검증에_실패하면_연결하지_않는다() {
        User me = localUser("me@test.com");
        willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN))
                .given(oauthVerificationService).verify(any(), any(), any());

        assertThatThrownBy(() -> socialAccountService.link(me.getId(), "kakao", ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
        assertThat(linkedAccounts(me.getId())).isEmpty();
    }

    @Test
    void 알_수_없는_제공자는_검증_관문에_들어가기_전에_400() {
        User me = localUser("me@test.com");

        assertThatThrownBy(() -> socialAccountService.link(me.getId(), "line", ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        verifyNoInteractions(oauthVerificationService);
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void 조회는_연결된_제공자와_비밀번호_보유_여부를_돌려준다() {
        User local = localUser("local@test.com");
        userSocialAccountRepository.save(UserSocialAccount.link(local, OAuthProvider.KAKAO, KAKAO_ID));
        User kakaoOnly = kakaoOnlyUser("kakao@test.com", "3000000002");

        SocialAccountsResponse localResponse = socialAccountService.getSocialAccounts(local.getId());
        SocialAccountsResponse kakaoResponse = socialAccountService.getSocialAccounts(kakaoOnly.getId());

        assertThat(localResponse.hasPassword()).isTrue();
        assertThat(localResponse.socialAccounts()).singleElement().satisfies(account -> {
            assertThat(account.provider()).isEqualTo(OAuthProvider.KAKAO);
            assertThat(account.linkedAt()).isNotNull();
        });
        assertThat(kakaoResponse.hasPassword()).isFalse();
        assertThat(kakaoResponse.socialAccounts()).hasSize(1);
    }

    @Test
    void 연결이_없는_로컬_가입자는_빈_목록() {
        User local = localUser("local@test.com");

        SocialAccountsResponse response = socialAccountService.getSocialAccounts(local.getId());

        assertThat(response.hasPassword()).isTrue();
        assertThat(response.socialAccounts()).isEmpty();
    }

    // ------------------------------------------------------------------ 해제

    @Test
    void 비밀번호가_있으면_마지막_소셜도_해제할_수_있다() {
        User local = localUser("local@test.com");
        userSocialAccountRepository.save(UserSocialAccount.link(local, OAuthProvider.KAKAO, KAKAO_ID));

        socialAccountService.unlink(local.getId(), "kakao");

        assertThat(linkedAccounts(local.getId())).isEmpty();
    }

    @Test
    void 소셜_전용_사용자의_유일한_소셜은_409_LAST_AUTH_METHOD() {
        User kakaoOnly = kakaoOnlyUser("kakao@test.com", KAKAO_ID);

        assertThatThrownBy(() -> socialAccountService.unlink(kakaoOnly.getId(), "kakao"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.LAST_AUTH_METHOD);
        assertThat(linkedAccounts(kakaoOnly.getId())).hasSize(1);
    }

    /** 인증 수단 = 소셜 2개(비밀번호 없음) → 하나는 해제되고, 남은 하나는 마지막 수단이라 막힌다(S-6). */
    @Test
    void 소셜_전용_사용자는_둘_중_하나를_해제할_수_있고_남은_하나는_409_LAST_AUTH_METHOD() {
        User me = kakaoAndGoogleUser("me@test.com");

        socialAccountService.unlink(me.getId(), "kakao");

        assertThat(linkedAccounts(me.getId()))
                .extracting(UserSocialAccount::getProvider)
                .containsExactly(OAuthProvider.GOOGLE);

        assertThatThrownBy(() -> socialAccountService.unlink(me.getId(), "google"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.LAST_AUTH_METHOD);
        assertThat(linkedAccounts(me.getId())).hasSize(1);
    }

    @Test
    void 연결되지_않은_제공자_해제는_404_SOCIAL_ACCOUNT_NOT_FOUND() {
        User local = localUser("local@test.com");

        assertThatThrownBy(() -> socialAccountService.unlink(local.getId(), "kakao"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOCIAL_ACCOUNT_NOT_FOUND);
    }
}
