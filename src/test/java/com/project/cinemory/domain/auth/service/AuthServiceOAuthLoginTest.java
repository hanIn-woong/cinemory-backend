package com.project.cinemory.domain.auth.service;

import com.project.cinemory.domain.auth.dto.OAuthLoginRequest;
import com.project.cinemory.domain.auth.dto.TokenResponse;
import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.entity.RefreshToken;
import com.project.cinemory.domain.auth.repository.RefreshTokenRepository;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtProperties;
import com.project.cinemory.global.security.JwtTokenProvider;
import com.project.cinemory.global.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code AuthService.oauthLogin}의 <b>조율 순서</b>를 고정한다.
 *
 * <p>검증 관문 내부의 순서(검증기 조회 → nonce 소비 → ID 토큰 검증)는 {@code OAuthVerificationServiceTest}가 지킨다
 * (2026-10-10 계정 연결과 관문을 공유하려고 분리 — account-integrity S-5). 여기서 지키는 것은 그 바깥이다:
 * <b>provider 판정 → 검증 → 가입/조회 → 토큰 발급</b>. 검증을 통과하지 못한 요청이 가입이나 발급까지 가면
 * 서명 없는 토큰으로 계정이 생긴다.
 *
 * <p>따라서 이 클래스의 테스트가 깨졌다면 테스트가 아니라 <b>구현 순서를 의심할 것.</b>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceOAuthLoginTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private static final String PROVIDER_PATH = "kakao"; // 경로 변수는 소문자로 온다
    private static final String ID_TOKEN = "kakao.id.token";
    private static final String NONCE = "nonce-abc";
    private static final long USER_ID = 7L;

    private static final OAuthUserInfo USER_INFO =
            new OAuthUserInfo("3000000001", "user@kakao.com", "카카오유저", "https://img.kakao/1.jpg");

    @Mock
    private UserService userService;
    @Mock
    private OAuthVerificationService oauthVerificationService;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    /** 해시는 가짜로 두면 "원문이 아니라 해시가 저장되는가"를 확인할 수 없어 실제 구현을 쓴다. */
    private final TokenHasher tokenHasher = new TokenHasher();
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private final JwtProperties jwtProperties = new JwtProperties(
            "0123456789012345678901234567890123", ACCESS_TTL, REFRESH_TTL, Duration.ofSeconds(10));

    private AuthService authService() {
        return new AuthService(userService, oauthVerificationService, refreshTokenRepository, jwtTokenProvider,
                tokenHasher, jwtProperties, clock);
    }

    private OAuthLoginRequest request() {
        return new OAuthLoginRequest(ID_TOKEN, NONCE);
    }

    private User savedUser() {
        User user = User.createOAuth(USER_INFO.email(), USER_INFO.nickname(), USER_INFO.profileImage());
        ReflectionTestUtils.setField(user, "id", USER_ID); // 영속화 전이라 id가 비어 있다
        return user;
    }

    private void givenVerified() {
        given(oauthVerificationService.verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE)).willReturn(USER_INFO);
    }

    // ------------------------------------------------------------ 조율 순서

    /**
     * <b>이 클래스의 핵심.</b> 네 단계가 이 순서로 진행되어야 한다.
     * {@code InOrder}는 호출 사이에 다른 호출이 끼는 것은 허용하되 <b>순서가 뒤집히면</b> 실패한다.
     */
    @Test
    void 성공하면_검증_가입_토큰생성_저장_순서로_진행된다() {
        AuthService authService = authService();
        givenVerified();
        given(userService.signUpOAuth(any(), any(), any(), any(), any())).willReturn(savedUser());
        given(jwtTokenProvider.createAccessToken(USER_ID, RoleType.USER)).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken()).willReturn("refresh-token");

        authService.oauthLogin(PROVIDER_PATH, request());

        InOrder inOrder = inOrder(oauthVerificationService, userService, jwtTokenProvider, refreshTokenRepository);
        inOrder.verify(oauthVerificationService).verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE);
        inOrder.verify(userService).signUpOAuth(USER_INFO.email(), USER_INFO.nickname(),
                USER_INFO.profileImage(), OAuthProvider.KAKAO, USER_INFO.providerId());
        inOrder.verify(jwtTokenProvider).createAccessToken(USER_ID, RoleType.USER);
        inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));
        inOrder.verifyNoMoreInteractions();
    }

    /** 알 수 없는 provider 이름은 검증 관문에 들어가기 전에 걸러야 한다 — 들어가면 nonce가 탄다. */
    @Test
    void 알_수_없는_provider면_검증_관문에_들어가지_않는다() {
        AuthService authService = authService();

        assertThatThrownBy(() -> authService.oauthLogin("line", request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);

        verifyNoInteractions(oauthVerificationService, userService, jwtTokenProvider, refreshTokenRepository);
    }

    // --------------------------------------------------------- 실패 시 차단

    /** 검증 실패는 가입도 발급도 막는다. 여기가 뚫리면 <b>서명 없는 토큰으로 계정이 생긴다.</b> */
    @Test
    void 검증에_실패하면_가입도_토큰_발급도_하지_않는다() {
        AuthService authService = authService();
        willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN))
                .given(oauthVerificationService).verify(any(), anyString(), anyString());

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);

        verifyNoInteractions(userService, jwtTokenProvider, refreshTokenRepository);
    }

    /** 이메일 충돌처럼 가입 단계에서 막힌 요청은 토큰을 남기지 않는다. */
    @Test
    void 가입에_실패하면_토큰을_발급하지도_저장하지도_않는다() {
        AuthService authService = authService();
        givenVerified();
        willThrow(new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED))
                .given(userService).signUpOAuth(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);

        verifyNoInteractions(jwtTokenProvider, refreshTokenRepository);
    }

    // ------------------------------------------------------- 단계 간 값 전달

    /**
     * 검증기가 뽑아낸 값이 <b>그대로</b> 가입으로 넘어가야 한다. 중간에 요청 본문의 값을 섞어 쓰면
     * 클라이언트가 보낸 이메일로 계정이 만들어져 ID 토큰 검증의 의미가 사라진다.
     *
     * <p>provider는 경로 변수({@code KaKaO})가 아니라 enum({@code KAKAO})으로 정규화된다 —
     * {@code user_social_account.provider}의 조회 키라 표기가 흔들리면 같은 사람이 매번 새로 가입된다.
     */
    @Test
    void 검증기가_돌려준_사용자_정보와_정규화된_provider를_가입에_넘긴다() {
        AuthService authService = authService();
        givenVerified();
        given(userService.signUpOAuth(any(), any(), any(), any(), any())).willReturn(savedUser());
        given(jwtTokenProvider.createRefreshToken()).willReturn("refresh-token");

        authService.oauthLogin("KaKaO", request()); // 대소문자가 섞여 와도 정규화된다

        verify(oauthVerificationService).verify(eq(OAuthProvider.KAKAO), eq(ID_TOKEN), eq(NONCE));
        verify(userService).signUpOAuth(
                USER_INFO.email(),
                USER_INFO.nickname(),
                USER_INFO.profileImage(),
                OAuthProvider.KAKAO,
                USER_INFO.providerId());
    }

    /**
     * 저장되는 것은 <b>해시</b>이고 클라이언트에게 가는 것은 <b>원문</b>이다.
     * 원문이 저장되면 DB 유출 시 그대로 재사용 가능한 값이 남는다.
     */
    @Test
    void 리프레시_토큰은_원문이_아니라_해시로_저장되고_만료는_now_더하기_TTL이다() {
        AuthService authService = authService();
        User user = savedUser();
        givenVerified();
        given(userService.signUpOAuth(any(), any(), any(), any(), any())).willReturn(user);
        given(jwtTokenProvider.createAccessToken(USER_ID, RoleType.USER)).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken()).willReturn("raw-refresh-token");

        TokenResponse response = authService.oauthLogin(PROVIDER_PATH, request());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(saved.getTokenHash())
                .isEqualTo(tokenHasher.hash("raw-refresh-token"))
                .isNotEqualTo("raw-refresh-token");
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getExpiresAt())
                .isEqualTo(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plus(REFRESH_TTL));
        assertThat(saved.isRevoked()).isFalse();

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(response.accessTokenExpiresIn()).isEqualTo(ACCESS_TTL.toSeconds());
    }
}
