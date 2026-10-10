package com.project.cinemory.domain.auth.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.service.oauth.OAuthIdTokenVerifier;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 소셜 검증 관문({@code OAuthVerificationService})의 <b>판정 순서</b>를 고정한다 — 로그인과 계정 연결이 함께 쓴다(S-5).
 * (2026-10-10 {@code AuthServiceOAuthLoginTest}에서 옮겨 왔다.)
 *
 * <p>어긋나면 생기는 구멍:
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
class OAuthVerificationServiceTest {

    private static final String ID_TOKEN = "kakao.id.token";
    private static final String NONCE = "nonce-abc";
    private static final OAuthUserInfo USER_INFO =
            new OAuthUserInfo("3000000001", "user@kakao.com", "카카오유저", "https://img.kakao/1.jpg");

    @Mock
    private OAuthNonceService nonceService;
    @Mock
    private OAuthIdTokenVerifier kakaoVerifier;
    @Mock
    private OAuthIdTokenVerifier googleVerifier;

    private OAuthVerificationService serviceWithKakaoVerifier() {
        given(kakaoVerifier.supports()).willReturn(OAuthProvider.KAKAO);
        return new OAuthVerificationService(nonceService, List.of(kakaoVerifier));
    }

    @Test
    void 성공하면_nonce를_소비한_뒤_ID_토큰을_검증하고_사용자_정보를_돌려준다() {
        OAuthVerificationService service = serviceWithKakaoVerifier();
        given(kakaoVerifier.verify(ID_TOKEN, NONCE)).willReturn(USER_INFO);

        OAuthUserInfo result = service.verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE);

        assertThat(result).isEqualTo(USER_INFO);
        InOrder inOrder = inOrder(nonceService, kakaoVerifier);
        inOrder.verify(nonceService).consumeOrThrow(NONCE);
        inOrder.verify(kakaoVerifier).verify(ID_TOKEN, NONCE);
    }

    /**
     * nonce 소비가 검증보다 <b>먼저</b>라는 사실은 실패 경로에서만 관측된다.
     * 검증이 터졌는데도 소비가 일어나 있어야 "시도 1회당 nonce 1개"가 성립한다.
     */
    @Test
    void ID_토큰_검증이_실패해도_nonce는_이미_소비돼_있다() {
        OAuthVerificationService service = serviceWithKakaoVerifier();
        willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN))
                .given(kakaoVerifier).verify(anyString(), anyString());

        assertThatThrownBy(() -> service.verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);

        verify(nonceService).consumeOrThrow(NONCE);
    }

    /** 소비에 실패한 nonce로는 검증기를 부르지 않는다 — 부르면 외부 JWK 조회가 낭비된다. */
    @Test
    void nonce_소비에_실패하면_ID_토큰을_검증하지_않는다() {
        OAuthVerificationService service = serviceWithKakaoVerifier();
        willThrow(new BusinessException(ErrorCode.INVALID_NONCE))
                .given(nonceService).consumeOrThrow(anyString());

        assertThatThrownBy(() -> service.verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);

        verify(kakaoVerifier, never()).verify(anyString(), anyString());
    }

    /**
     * 검증기가 둘 이상이어도 요청한 제공자의 검증기로 가고, 순서(조회 → nonce 소비 → 검증)는 같다
     * (account-integrity D-5-D). 다른 제공자의 검증기는 건드리지 않는다.
     */
    @Test
    void GOOGLE_요청은_구글_검증기로_같은_순서를_지난다() {
        given(kakaoVerifier.supports()).willReturn(OAuthProvider.KAKAO);
        given(googleVerifier.supports()).willReturn(OAuthProvider.GOOGLE);
        OAuthVerificationService service =
                new OAuthVerificationService(nonceService, List.of(kakaoVerifier, googleVerifier));
        given(googleVerifier.verify("google.id.token", NONCE)).willReturn(USER_INFO);

        OAuthUserInfo result = service.verify(OAuthProvider.GOOGLE, "google.id.token", NONCE);

        assertThat(result).isEqualTo(USER_INFO);
        InOrder inOrder = inOrder(nonceService, googleVerifier);
        inOrder.verify(nonceService).consumeOrThrow(NONCE);
        inOrder.verify(googleVerifier).verify("google.id.token", NONCE);
        verify(kakaoVerifier, never()).verify(anyString(), anyString());
    }

    /**
     * enum에는 값이 있는데 검증기 빈이 없는 구성 사고(값만 추가하고 구현을 잊은 경우).
     * 이때도 사용자의 nonce는 살아 있어야 한다 — 서버 구성 문제로 재발급을 강요할 이유가 없다.
     */
    @Test
    void 검증기가_등록되지_않았으면_nonce를_소비하기_전에_거부한다() {
        OAuthVerificationService service = new OAuthVerificationService(nonceService, List.of());

        assertThatThrownBy(() -> service.verify(OAuthProvider.KAKAO, ID_TOKEN, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);

        verifyNoInteractions(nonceService);
    }
}
