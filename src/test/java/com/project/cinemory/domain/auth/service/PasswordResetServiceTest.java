package com.project.cinemory.domain.auth.service;

import com.project.cinemory.domain.auth.entity.PasswordResetToken;
import com.project.cinemory.domain.auth.entity.RevokedReason;
import com.project.cinemory.domain.auth.repository.PasswordResetTokenRepository;
import com.project.cinemory.domain.auth.repository.RefreshTokenRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.mail.PasswordResetMailSender;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code PasswordResetService}가 고정하는 것은 대부분 <b>단계의 순서</b>다.
 * 각 단계는 그 자체로는 모두 "맞는" 규칙이라 순서가 뒤집혀도 컴파일도 통과하고
 * 겉보기 동작도 정상인데, 조합이 틀리면 한쪽이 조용히 무력화된다.
 *
 * <ol>
 *   <li><b>억제 판정이 미사용 토큰 삭제보다 늦어지면</b>(F-1) — 판정 근거인 {@code created_at}이
 *       함께 지워져 재요청 억제가 사실상 사라진다. 메일 폭탄을 막지 못한다.</li>
 *   <li><b>세션 폐기가 비밀번호 변경보다 빨라지면</b>(S-10 ③-④) — 폐기는 {@code REQUIRES_NEW}라
 *       별도 트랜잭션에서 커밋되므로, 뒤가 롤백되면 "로그아웃됐는데 비밀번호는 그대로"가 된다.</li>
 *   <li><b>요청 단계가 계정 상태에 따라 다르게 응답하면</b> — 이메일 열거가 가능해진다.
 *       그래서 미가입·소셜·억제 창은 전부 <b>조용한 반환</b>이어야 하고 예외가 아니다.</li>
 * </ol>
 *
 * <p>따라서 이 클래스가 깨졌다면 테스트가 아니라 <b>구현 순서를 의심할 것.</b>
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration COOLDOWN = Duration.ofMinutes(3);

    private static final String EMAIL = "local@cinemory.com";
    private static final String NEW_PASSWORD = "newPassword123";
    private static final long USER_ID = 42L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserService userService;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordResetMailSender mailSender;

    /** 해시를 가짜로 두면 "원문이 아니라 해시가 저장되는가"를 확인할 수 없어 실제 구현을 쓴다. */
    private final TokenHasher tokenHasher = new TokenHasher();
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final LocalDateTime now = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    private PasswordResetService service() {
        return new PasswordResetService(userRepository, userService, passwordResetTokenRepository,
                refreshTokenRepository, mailSender, tokenHasher, clock, TTL, COOLDOWN);
    }

    private User localUser() {
        User user = User.createLocal(EMAIL, "$2a$10$oldHash", "로컬유저");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private User kakaoUser() {
        User user = User.createOAuth(EMAIL, "카카오유저", null, "KAKAO", "3000000001");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    /** 발급 이력이 없는 상태(첫 요청)로 세팅한다. */
    private void givenNoPreviousToken() {
        given(passwordResetTokenRepository.findLatestCreatedAtByUserId(USER_ID))
                .willReturn(Optional.empty());
    }

    private PasswordResetToken storedToken(User user, String rawToken, LocalDateTime expiresAt) {
        return PasswordResetToken.issue(user, tokenHasher.hash(rawToken), expiresAt);
    }

    // =========================================================== ① 요청

    /**
     * <b>F-1의 핵심.</b> 억제 판정이 미사용 토큰 삭제보다 <b>먼저</b>여야 한다.
     * 뒤바뀌면 판정 근거가 함께 지워져 억제가 조용히 무력화된다.
     */
    @Test
    void 요청은_억제판정_미사용삭제_저장_발송_순서로_진행된다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(localUser()));
        givenNoPreviousToken();

        service().requestReset(EMAIL);

        InOrder inOrder = inOrder(passwordResetTokenRepository, mailSender);
        inOrder.verify(passwordResetTokenRepository).findLatestCreatedAtByUserId(USER_ID);
        inOrder.verify(passwordResetTokenRepository).deleteUnusedByUserId(USER_ID);
        inOrder.verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        inOrder.verify(mailSender).send(anyString(), anyString(), any(Duration.class));
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * 저장되는 것은 <b>해시</b>이고 메일로 나가는 것은 <b>원문</b>이다.
     * 원문이 저장되면 DB 유출 시 그대로 재설정에 쓸 수 있는 값이 남는다.
     */
    @Test
    void 토큰은_해시로_저장되고_원문은_메일로만_나가며_만료는_now_더하기_TTL이다() {
        User user = localUser();
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));
        givenNoPreviousToken();

        service().requestReset(EMAIL);

        ArgumentCaptor<PasswordResetToken> savedCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(savedCaptor.capture());
        ArgumentCaptor<String> rawTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(EMAIL), rawTokenCaptor.capture(), any(Duration.class));

        PasswordResetToken saved = savedCaptor.getValue();
        String rawToken = rawTokenCaptor.getValue();

        assertThat(saved.getTokenHash())
                .isEqualTo(tokenHasher.hash(rawToken))
                .isNotEqualTo(rawToken);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getExpiresAt()).isEqualTo(now.plus(TTL));
        assertThat(saved.isUsed()).isFalse();
        // 256bit를 Base64URL(패딩 없음)로 인코딩하면 43자다
        assertThat(rawToken).hasSize(43);
    }

    /** 가입되지 않은 이메일도 <b>예외 없이</b> 조용히 끝나야 한다 — 예외는 곧 계정 존재 신호다. */
    @Test
    void 가입되지_않은_이메일이면_아무것도_하지_않고_예외도_던지지_않는다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

        service().requestReset(EMAIL);

        verifyNoInteractions(passwordResetTokenRepository, mailSender);
    }

    /**
     * 소셜 계정에는 재설정할 비밀번호 자체가 없다. 여기서 응답이나 동작이 갈리면
     * "이 주소는 카카오로 가입돼 있다"가 새어 나간다.
     */
    @Test
    void 소셜_가입_계정이면_토큰도_메일도_만들지_않는다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(kakaoUser()));

        service().requestReset(EMAIL);

        verifyNoInteractions(passwordResetTokenRepository, mailSender);
    }

    /**
     * <b>F-1이 실제로 관측되는 지점.</b> 억제에 걸린 요청은 삭제도 하지 않아야 한다 —
     * 삭제만 일어나면 유효한 링크가 사라져 "메일은 안 오는데 기존 링크도 죽는" 최악이 된다.
     */
    @Test
    void 억제_창_안이면_미사용_토큰을_지우지도_발송하지도_않는다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(localUser()));
        // 1분 전에 이미 발급했다 (쿨다운 3분)
        given(passwordResetTokenRepository.findLatestCreatedAtByUserId(USER_ID))
                .willReturn(Optional.of(now.minusMinutes(1)));

        service().requestReset(EMAIL);

        verify(passwordResetTokenRepository, never()).deleteUnusedByUserId(anyLong());
        verify(passwordResetTokenRepository, never()).save(any());
        verifyNoInteractions(mailSender);
    }

    /** 경계값 — 쿨다운이 정확히 지난 시점은 억제하지 않는다(그러지 않으면 영원히 못 받는 구간이 생긴다). */
    @Test
    void 쿨다운이_정확히_지났으면_다시_발송한다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(localUser()));
        given(passwordResetTokenRepository.findLatestCreatedAtByUserId(USER_ID))
                .willReturn(Optional.of(now.minus(COOLDOWN)));

        service().requestReset(EMAIL);

        verify(mailSender).send(anyString(), anyString(), any(Duration.class));
    }

    /**
     * <b>F-2.</b> 발송 실패를 삼키면 토큰만 남아, 사용자는 메일을 못 받은 채 재요청해도
     * 억제에 걸려 아무것도 할 수 없다. 예외를 그대로 올려 트랜잭션을 롤백시킨다.
     */
    @Test
    void 메일_발송이_실패하면_예외를_전파해_토큰까지_롤백시킨다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(localUser()));
        givenNoPreviousToken();
        willThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR))
                .given(mailSender).send(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service().requestReset(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    // =========================================================== ② 사전 검증

    /** 사전 검증은 <b>소비하지 않는다.</b> 소비하면 링크가 확인 한 번에 죽어 재설정을 못 한다. */
    @Test
    void 사전_검증은_토큰을_소비하지_않는다() {
        PasswordResetToken token = storedToken(localUser(), "raw-token", now.plusMinutes(10));
        given(passwordResetTokenRepository.findByTokenHash(tokenHasher.hash("raw-token")))
                .willReturn(Optional.of(token));

        service().verifyToken("raw-token");

        assertThat(token.isUsed()).isFalse();
        verifyNoInteractions(userService, refreshTokenRepository);
    }

    @Test
    void 사전_검증에서_만료된_토큰은_INVALID_RESET_TOKEN이다() {
        given(passwordResetTokenRepository.findByTokenHash(anyString()))
                .willReturn(Optional.of(storedToken(localUser(), "raw-token", now.minusSeconds(1))));

        assertThatThrownBy(() -> service().verifyToken("raw-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_RESET_TOKEN);
    }

    // =========================================================== ③ 확정

    /**
     * <b>세션 폐기가 마지막이라는 사실을 고정한다.</b> {@code revokeAllByUserId}는
     * {@code REQUIRES_NEW}라 별도 트랜잭션에서 커밋되므로, 앞으로 당기면 뒤가 실패했을 때
     * "로그아웃만 되고 비밀번호는 그대로"인 상태가 남는다.
     */
    @Test
    void 확정은_비밀번호_변경_사용처리_세션폐기_순서로_진행된다() {
        User user = localUser();
        PasswordResetToken token = storedToken(user, "raw-token", now.plusMinutes(10));
        given(passwordResetTokenRepository.findByTokenHash(tokenHasher.hash("raw-token")))
                .willReturn(Optional.of(token));

        service().confirmReset("raw-token", NEW_PASSWORD);

        InOrder inOrder = inOrder(userService, refreshTokenRepository);
        inOrder.verify(userService).updatePassword(user, NEW_PASSWORD);
        inOrder.verify(refreshTokenRepository)
                .revokeAllByUserId(USER_ID, now, RevokedReason.PASSWORD_CHANGED);
        inOrder.verifyNoMoreInteractions();

        assertThat(token.getUsedAt()).isEqualTo(now);
    }

    /**
     * 앞 단계가 실패하면 폐기가 <b>아예 일어나지 않아야</b> 한다.
     * 순서를 뒤집으면 이 테스트만 깨지고 나머지는 전부 통과한다 — 그래서 이 테스트가 필요하다.
     */
    @Test
    void 비밀번호_변경이_실패하면_세션을_폐기하지_않는다() {
        PasswordResetToken token = storedToken(localUser(), "raw-token", now.plusMinutes(10));
        given(passwordResetTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(token));
        willThrow(new IllegalStateException("소셜 로그인 계정은 비밀번호를 가질 수 없습니다."))
                .given(userService).updatePassword(any(), anyString());

        assertThatThrownBy(() -> service().confirmReset("raw-token", NEW_PASSWORD))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(refreshTokenRepository);
        assertThat(token.isUsed()).isFalse();
    }

    @Test
    void 존재하지_않는_토큰이면_INVALID_RESET_TOKEN이고_아무것도_바꾸지_않는다() {
        given(passwordResetTokenRepository.findByTokenHash(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().confirmReset("raw-token", NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_RESET_TOKEN);

        verifyNoInteractions(userService, refreshTokenRepository);
    }

    /** 만료와 사용됨을 <b>구분하지 않는다</b> — 구분하면 "이 토큰은 존재했다"가 새어 나간다. */
    @Test
    void 만료된_토큰이면_INVALID_RESET_TOKEN이다() {
        given(passwordResetTokenRepository.findByTokenHash(anyString()))
                .willReturn(Optional.of(storedToken(localUser(), "raw-token", now.minusSeconds(1))));

        assertThatThrownBy(() -> service().confirmReset("raw-token", NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_RESET_TOKEN);

        verifyNoInteractions(userService, refreshTokenRepository);
    }

    @Test
    void 이미_사용된_토큰이면_만료와_같은_INVALID_RESET_TOKEN이다() {
        PasswordResetToken token = storedToken(localUser(), "raw-token", now.plusMinutes(10));
        token.markAsUsed(now.minusMinutes(1));
        given(passwordResetTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(token));

        assertThatThrownBy(() -> service().confirmReset("raw-token", NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_RESET_TOKEN);

        verifyNoInteractions(userService, refreshTokenRepository);
        // 재사용 시도가 최초 사용 시각을 덮어쓰지 않는다(감사 기록 보존)
        assertThat(token.getUsedAt()).isEqualTo(now.minusMinutes(1));
    }


    // =========================================================== 설정 검증

    /** 잘못된 설정은 기동 시점에 실패시킨다 — 0이면 발급 즉시 만료돼 아무도 재설정할 수 없다. */
    @Test
    void TTL이_0이하면_기동_시점에_실패한다() {
        assertThatThrownBy(() -> new PasswordResetService(userRepository, userService,
                passwordResetTokenRepository, refreshTokenRepository, mailSender, tokenHasher, clock,
                Duration.ZERO, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.password-reset.ttl");
    }

    @Test
    void 쿨다운이_음수면_기동_시점에_실패한다() {
        assertThatThrownBy(() -> new PasswordResetService(userRepository, userService,
                passwordResetTokenRepository, refreshTokenRepository, mailSender, tokenHasher, clock,
                TTL, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.password-reset.resend-cooldown");
    }
}
