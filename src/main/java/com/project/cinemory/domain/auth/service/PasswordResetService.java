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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 비로그인 비밀번호 재설정 (S-J). 로그인 상태의 <b>변경</b>(Step5, {@code UserController} 소관)과는
 * 다른 기능이며, 자격증명이 "현재 비밀번호"가 아니라 <b>메일로 받은 일회용 토큰</b>이라는 점이 다르다.
 *
 * <p>{@code AuthService}에 넣지 않은 이유 — 저쪽은 "자격증명 → 세션 토큰 발급"을 조율하지만
 * 여기는 토큰을 <b>발급하지 않는다</b>(F-4). 협력자 구성도 겹치지 않는다.
 *
 * <p><b>이메일 열거 방지가 이 클래스의 제1 제약이다.</b> 요청 엔드포인트는 계정 존재 여부·가입 방식과
 * 무관하게 항상 같은 200을 돌려준다. 아래 조기 반환들이 그래서 예외가 아니라 조용한 반환이다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PasswordResetService {

    /** 리프레시 토큰과 같은 엔트로피 기준(256bit). Base64URL 인코딩 시 패딩 없이 43자다. */
    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetMailSender mailSender;
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final Duration ttl;
    private final Duration resendCooldown;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository,
                                UserService userService,
                                PasswordResetTokenRepository passwordResetTokenRepository,
                                RefreshTokenRepository refreshTokenRepository,
                                PasswordResetMailSender mailSender,
                                TokenHasher tokenHasher,
                                Clock clock,
                                @Value("${auth.password-reset.ttl}") Duration ttl,
                                @Value("${auth.password-reset.resend-cooldown}") Duration resendCooldown) {
        requirePositive(ttl, "auth.password-reset.ttl");
        requirePositive(resendCooldown, "auth.password-reset.resend-cooldown");

        this.userRepository = userRepository;
        this.userService = userService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.mailSender = mailSender;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.ttl = ttl;
        this.resendCooldown = resendCooldown;
    }

    /**
     * 재설정 메일 요청. <b>어떤 경우에도 예외를 던지지 않는다</b> — 실패를 알리는 것 자체가
     * "이 이메일은 가입돼 있다" 또는 "이 계정은 소셜이다"를 알려주는 일이기 때문이다.
     * (메일 발송 실패는 예외다 — 아래 참고)
     *
     * <p><b>⚠️ 단계 순서가 설계의 핵심이다</b>(S-9 F-1).
     * <pre>
     *   ① 억제 판정 — 마지막 발급이 쿨다운 안이면 아무것도 하지 않는다
     *   ② 미사용 토큰 삭제 — ①을 통과했을 때만
     *   ③ 발급 + 저장 + 발송 (같은 트랜잭션)
     * </pre>
     * ②를 먼저 하면 ①이 볼 {@code created_at}이 함께 지워져 <b>억제가 조용히 무력화된다.</b>
     * 남는 것은 오래된 "사용됨" 행뿐이라 쿨다운에 걸리지 않고, 메일 폭탄을 막지 못한다.
     *
     * <p><b>메일 발송 실패는 롤백시킨다</b>(F-2). 토큰만 남으면 사용자는 메일을 못 받은 채
     * 재요청하는데 ①에 걸려 아무것도 할 수 없다. 롤백하면 {@code created_at}도 사라져 즉시
     * 재요청이 가능하다. 이때만 응답이 200이 아니게 되는데, 이는 SMTP 장애라는
     * 인프라 사건이지 계정 정보가 아니다.
     */
    @Transactional
    public void requestReset(String email) {
        LocalDateTime now = LocalDateTime.now(clock);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            log.debug("비밀번호 재설정 요청 - 가입되지 않은 이메일");
            return;
        }
        // 소셜 계정에는 재설정할 비밀번호 자체가 없다(chk_user_auth_method: 로컬 XOR 소셜).
        if (user.isOAuthUser()) {
            log.debug("비밀번호 재설정 요청 - 소셜 가입 계정이라 발송하지 않음 userId={}", user.getId());
            return;
        }
        if (isWithinResendCooldown(user.getId(), now)) {   // ①
            log.debug("비밀번호 재설정 요청 - 재요청 억제 창 내라 발송하지 않음 userId={}", user.getId());
            return;
        }

        passwordResetTokenRepository.deleteUnusedByUserId(user.getId());   // ②

        String rawToken = generateToken();                                 // ③
        passwordResetTokenRepository.save(
                PasswordResetToken.issue(user, tokenHasher.hash(rawToken), now.plus(ttl)));
        mailSender.send(user.getEmail(), rawToken, ttl);
    }

    /**
     * 딥링크 진입 직후의 사전 검증 (F-3). <b>토큰을 소비하지 않는다</b> —
     * 소비하면 확인 한 번에 링크가 죽어 정작 재설정을 못 한다.
     *
     * @throws BusinessException {@code INVALID_RESET_TOKEN}
     */
    public void verifyToken(String rawToken) {
        findUsableTokenOrThrow(rawToken, LocalDateTime.now(clock));
    }

    /**
     * 재설정 확정. <b>새 토큰을 발급하지 않는다</b>(F-4) — 방금 비밀번호를 바꾼 상황이고,
     * 아래의 전체 세션 폐기와도 모순된다. 클라이언트는 로그인 화면으로 보낸다.
     *
     * <p><b>⚠️ 세션 폐기가 반드시 마지막이다.</b> {@code revokeAllByUserId}는 {@code REQUIRES_NEW}라
     * <b>별도 트랜잭션에서 즉시 커밋</b>된다. 먼저 호출하면 뒤 단계가 롤백돼도 폐기는 남아
     * <b>"로그아웃됐는데 비밀번호는 그대로"</b>인 상태가 만들어진다.
     * 앞이 실패하면 폐기가 아예 일어나지 않도록 순서를 고정한다.
     *
     * @throws BusinessException {@code INVALID_RESET_TOKEN} — 미존재 / 만료 / 이미 사용됨
     */
    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        LocalDateTime now = LocalDateTime.now(clock);
        PasswordResetToken token = findUsableTokenOrThrow(rawToken, now);
        User user = token.getUser();

        userService.updatePassword(user, newPassword);
        token.markAsUsed(now);

        // 비밀번호가 노출됐다는 전제의 흐름이므로 기존 세션을 전부 끊는다.
        refreshTokenRepository.revokeAllByUserId(user.getId(), now, RevokedReason.PASSWORD_CHANGED);
    }

    /**
     * <b>미존재 / 만료 / 이미 사용됨을 구분하지 않는다.</b> 사용자에게는 셋 다
     * "이 링크는 더 못 쓴다"로 같고, 구분하면 "이 토큰은 존재했다"는 정보가 새어
     * 토큰 탐색의 단서가 된다.
     */
    private PasswordResetToken findUsableTokenOrThrow(String rawToken, LocalDateTime now) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_RESET_TOKEN));

        if (!token.isUsable(now)) {
            throw new BusinessException(ErrorCode.INVALID_RESET_TOKEN);
        }
        return token;
    }

    /** 이력이 아예 없으면 억제하지 않는다(첫 요청). */
    private boolean isWithinResendCooldown(Long userId, LocalDateTime now) {
        return passwordResetTokenRepository.findLatestCreatedAtByUserId(userId)
                .filter(latestCreatedAt -> latestCreatedAt.plus(resendCooldown).isAfter(now))
                .isPresent();
    }

    /** 원문은 메일로만 나가고 DB에는 이 값의 SHA-256 해시만 남는다. */
    private String generateToken() {
        byte[] entropy = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + "은(는) 0보다 큰 Duration이어야 합니다.");
        }
    }
}
