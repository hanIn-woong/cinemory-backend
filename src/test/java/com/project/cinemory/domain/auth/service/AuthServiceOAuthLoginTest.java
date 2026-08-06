package com.project.cinemory.domain.auth.service;

import com.project.cinemory.domain.auth.dto.OAuthLoginRequest;
import com.project.cinemory.domain.auth.dto.TokenResponse;
import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.entity.RefreshToken;
import com.project.cinemory.domain.auth.repository.RefreshTokenRepository;
import com.project.cinemory.domain.auth.service.oauth.OAuthIdTokenVerifier;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code AuthService.oauthLogin}의 <b>조율 순서</b>를 고정한다.
 *
 * <p>협력자 각각(nonce 서비스 / 검증기 / 회원 서비스 / 토큰 발급기)은 자기 테스트에서 이미
 * 검증된다. 여기서 지키는 것은 <b>"누가 언제 불리는가"</b>다 — 이 순서는 컴파일러도
 * 개별 협력자 테스트도 잡아주지 못하는데, 어긋나면 다음 두 구멍이 생긴다.
 *
 * <ol>
 *   <li><b>nonce 소비가 ID 토큰 검증보다 늦어지면</b> — 검증 실패 시 nonce가 캐시에 남아
 *       같은 nonce로 토큰만 바꿔가며 반복 시도할 수 있다. 1회 시도 = nonce 1개라는 전제가 깨진다.</li>
 *   <li><b>검증기 조회가 nonce 소비보다 늦어지면</b> — 지원하지 않는 provider로 온 요청이
 *       정상 발급된 nonce를 태워버린다. 사용자는 400을 받고도 nonce를 다시 받아야 한다.</li>
 * </ol>
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
    private OAuthNonceService nonceService;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private OAuthIdTokenVerifier kakaoVerifier;

    /** 해시는 가짜로 두면 "원문이 아니라 해시가 저장되는가"를 확인할 수 없어 실제 구현을 쓴다. */
    private final TokenHasher tokenHasher = new TokenHasher();
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private final JwtProperties jwtProperties = new JwtProperties(
            "0123456789012345678901234567890123", ACCESS_TTL, REFRESH_TTL, Duration.ofSeconds(10));

    private AuthService authService(OAuthIdTokenVerifier... verifiers) {
        return new AuthService(userService, nonceService, refreshTokenRepository, jwtTokenProvider,
                tokenHasher, jwtProperties, clock, List.of(verifiers));
    }

    /** 검증기가 카카오를 지원한다고 선언한 정상 구성. */
    private AuthService serviceWithKakaoVerifier() {
        given(kakaoVerifier.supports()).willReturn(OAuthProvider.KAKAO);
        return authService(kakaoVerifier);
    }

    private OAuthLoginRequest request() {
        return new OAuthLoginRequest(ID_TOKEN, NONCE);
    }

    private User savedUser() {
        User user = User.createOAuth(USER_INFO.email(), USER_INFO.nickname(), USER_INFO.profileImage(),
                OAuthProvider.KAKAO.name(), USER_INFO.providerId());
        ReflectionTestUtils.setField(user, "id", USER_ID); // 영속화 전이라 id가 비어 있다
        return user;
    }

    // ------------------------------------------------------------ 조율 순서

    /**
     * <b>이 클래스의 핵심.</b> 다섯 단계가 이 순서로 진행되어야 한다.
     * {@code InOrder}는 호출 사이에 다른 호출이 끼는 것은 허용하되 <b>순서가 뒤집히면</b> 실패한다.
     */
    @Test
    void 성공하면_nonce소비_토큰검증_가입_토큰생성_저장_순서로_진행된다() {
        AuthService authService = serviceWithKakaoVerifier();
        given(kakaoVerifier.verify(ID_TOKEN, NONCE)).willReturn(USER_INFO);
        given(userService.signUpOAuth(any(), any(), any(), any(), any())).willReturn(savedUser());
        given(jwtTokenProvider.createAccessToken(USER_ID, RoleType.USER)).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken()).willReturn("refresh-token");

        authService.oauthLogin(PROVIDER_PATH, request());

        InOrder inOrder = inOrder(nonceService, kakaoVerifier, userService, jwtTokenProvider, refreshTokenRepository);
        inOrder.verify(nonceService).consumeOrThrow(NONCE);
        inOrder.verify(kakaoVerifier).verify(ID_TOKEN, NONCE);
        inOrder.verify(userService).signUpOAuth(USER_INFO.email(), USER_INFO.nickname(),
                USER_INFO.profileImage(), OAuthProvider.KAKAO.name(), USER_INFO.providerId());
        inOrder.verify(jwtTokenProvider).createAccessToken(USER_ID, RoleType.USER);
        inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * nonce 소비가 검증보다 <b>먼저</b>라는 사실은 실패 경로에서만 관측된다.
     * 검증이 터졌는데도 소비가 일어나 있어야 "시도 1회당 nonce 1개"가 성립한다.
     */
    @Test
    void ID_토큰_검증이_실패해도_nonce는_이미_소비돼_있다() {
        AuthService authService = serviceWithKakaoVerifier();
        willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN))
                .given(kakaoVerifier).verify(anyString(), anyString());

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);

        verify(nonceService).consumeOrThrow(NONCE);
    }

    /** 소비에 실패한 nonce로는 검증기를 부르지 않는다 — 부르면 외부 JWK 조회가 낭비된다. */
    @Test
    void nonce_소비에_실패하면_ID_토큰을_검증하지_않는다() {
        AuthService authService = serviceWithKakaoVerifier();
        willThrow(new BusinessException(ErrorCode.INVALID_NONCE))
                .given(nonceService).consumeOrThrow(anyString());

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);

        verify(kakaoVerifier, never()).verify(anyString(), anyString());
        verifyNoInteractions(userService, jwtTokenProvider, refreshTokenRepository);
    }

    // ------------------------------------------------- provider 판정이 먼저다

    /** 알 수 없는 provider 이름은 nonce를 태우기 전에 걸러야 한다. */
    @Test
    void 알_수_없는_provider면_nonce를_소비하지_않는다() {
        AuthService authService = serviceWithKakaoVerifier();

        assertThatThrownBy(() -> authService.oauthLogin("google", request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);

        verifyNoInteractions(nonceService, userService, jwtTokenProvider, refreshTokenRepository);
        verify(kakaoVerifier, never()).verify(anyString(), anyString());
    }

    /**
     * enum에는 값이 있는데 검증기 빈이 없는 구성 사고(값만 추가하고 구현을 잊은 경우).
     * 이때도 사용자의 nonce는 살아 있어야 한다 — 서버 구성 문제로 재발급을 강요할 이유가 없다.
     */
    @Test
    void 검증기가_등록되지_않았으면_nonce를_소비하기_전에_거부한다() {
        AuthService authService = authService(); // 검증기 없이 기동된 상태

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);

        verifyNoInteractions(nonceService, userService, jwtTokenProvider, refreshTokenRepository);
    }

    // --------------------------------------------------------- 실패 시 차단

    /** 검증 실패는 가입도 발급도 막는다. 여기가 뚫리면 <b>서명 없는 토큰으로 계정이 생긴다.</b> */
    @Test
    void 검증에_실패하면_가입도_토큰_발급도_하지_않는다() {
        AuthService authService = serviceWithKakaoVerifier();
        willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN))
                .given(kakaoVerifier).verify(anyString(), anyString());

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(userService, jwtTokenProvider, refreshTokenRepository);
    }

    /** 이메일 충돌(로컬 가입 계정)처럼 가입 단계에서 막힌 요청은 토큰을 남기지 않는다. */
    @Test
    void 가입에_실패하면_토큰을_발급하지도_저장하지도_않는다() {
        AuthService authService = serviceWithKakaoVerifier();
        given(kakaoVerifier.verify(ID_TOKEN, NONCE)).willReturn(USER_INFO);
        willThrow(new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED_LOCALLY))
                .given(userService).signUpOAuth(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> authService.oauthLogin(PROVIDER_PATH, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED_LOCALLY);

        verifyNoInteractions(jwtTokenProvider, refreshTokenRepository);
    }

    // ------------------------------------------------------- 단계 간 값 전달

    /**
     * 검증기가 뽑아낸 값이 <b>그대로</b> 가입으로 넘어가야 한다. 중간에 요청 본문의 값을 섞어 쓰면
     * 클라이언트가 보낸 이메일로 계정이 만들어져 ID 토큰 검증의 의미가 사라진다.
     *
     * <p>provider는 경로 변수({@code kakao})가 아니라 enum 이름({@code KAKAO})으로 저장된다 —
     * {@code user.provider}의 조회 키라 표기가 흔들리면 같은 사람이 매번 새로 가입된다.
     */
    @Test
    void 검증기가_돌려준_사용자_정보와_정규화된_provider_이름을_가입에_넘긴다() {
        AuthService authService = serviceWithKakaoVerifier();
        given(kakaoVerifier.verify(ID_TOKEN, NONCE)).willReturn(USER_INFO);
        given(userService.signUpOAuth(any(), any(), any(), any(), any())).willReturn(savedUser());
        given(jwtTokenProvider.createRefreshToken()).willReturn("refresh-token");

        authService.oauthLogin("KaKaO", request()); // 대소문자가 섞여 와도 정규화된다

        verify(userService).signUpOAuth(
                USER_INFO.email(),
                USER_INFO.nickname(),
                USER_INFO.profileImage(),
                "KAKAO",
                USER_INFO.providerId());
    }

    /**
     * 저장되는 것은 <b>해시</b>이고 클라이언트에게 가는 것은 <b>원문</b>이다.
     * 원문이 저장되면 DB 유출 시 그대로 재사용 가능한 값이 남는다.
     */
    @Test
    void 리프레시_토큰은_원문이_아니라_해시로_저장되고_만료는_now_더하기_TTL이다() {
        AuthService authService = serviceWithKakaoVerifier();
        User user = savedUser();
        given(kakaoVerifier.verify(ID_TOKEN, NONCE)).willReturn(USER_INFO);
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
