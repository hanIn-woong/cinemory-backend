package com.project.cinemory.domain.auth.service;

import com.project.cinemory.domain.auth.dto.LoginRequest;
import com.project.cinemory.domain.auth.dto.OAuthLoginRequest;
import com.project.cinemory.domain.auth.dto.TokenResponse;
import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.entity.RefreshToken;
import com.project.cinemory.domain.auth.entity.RevokedReason;
import com.project.cinemory.domain.auth.repository.RefreshTokenRepository;
import com.project.cinemory.domain.auth.service.oauth.OAuthIdTokenVerifier;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtProperties;
import com.project.cinemory.global.security.JwtTokenProvider;
import com.project.cinemory.global.security.TokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 인증 흐름 조율 — 로그인 / 재발급 / 로그아웃.
 *
 * <p>자격증명 판정은 {@code UserService}, 토큰 생성은 {@code JwtTokenProvider}가 맡고
 * 여기서는 <b>둘을 엮어 토큰을 발급하고 리프레시 토큰의 수명을 관리</b>한다.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserService userService;
    private final OAuthNonceService nonceService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenHasher tokenHasher;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final Map<OAuthProvider, OAuthIdTokenVerifier> verifiers;

    /**
     * {@code Map<OAuthProvider, OAuthIdTokenVerifier>}를 직접 주입받지 않는 이유:
     * Spring의 Map 자동 주입은 키가 <b>빈 이름(String)</b>이라 enum 키로 쓸 수 없다.
     * List로 받아 생성자에서 변환하면 필드를 final로 유지할 수 있다 (4-6 {@code CommentService}와 동일).
     */
    public AuthService(UserService userService,
                       OAuthNonceService nonceService,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       TokenHasher tokenHasher,
                       JwtProperties jwtProperties,
                       Clock clock,
                       List<OAuthIdTokenVerifier> verifiers) {
        this.userService = userService;
        this.nonceService = nonceService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenHasher = tokenHasher;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.verifiers = new EnumMap<>(OAuthProvider.class);
        verifiers.forEach(verifier -> this.verifiers.put(verifier.supports(), verifier));
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userService.login(request.email(), request.password());
        return issueTokens(user, LocalDateTime.now(clock));
    }

    /**
     * 소셜 로그인 — 클라이언트 SDK가 받아온 ID 토큰을 서버가 검증하고 자체 JWT를 발급한다.
     *
     * <p><b>nonce 소비를 ID 토큰 검증보다 먼저 한다.</b> 순서를 바꾸면 검증이 실패할 때
     * nonce가 캐시에 남아 <b>같은 nonce로 토큰만 바꿔가며 반복 시도</b>할 수 있다.
     * 소비를 먼저 하면 시도 1회당 nonce 1개가 강제된다 — 검증 실패 시 사용자는 nonce를 새로
     * 받아야 하지만, 실패한 시도의 nonce를 재사용하게 두면 안 되므로 의도된 동작이다.
     */
    @Transactional
    public TokenResponse oauthLogin(String providerName, OAuthLoginRequest request) {
        OAuthProvider provider = OAuthProvider.from(providerName);

        OAuthIdTokenVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            // enum에는 있으나 구현체가 없는 경우 — 값을 먼저 추가하고 구현을 잊었을 때 여기로 온다
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }

        nonceService.consumeOrThrow(request.nonce());
        OAuthUserInfo userInfo = verifier.verify(request.idToken(), request.nonce());

        User user = userService.signUpOAuth(
                userInfo.email(),
                userInfo.nickname(),
                userInfo.profileImage(),
                provider.name(),
                userInfo.providerId());

        return issueTokens(user, LocalDateTime.now(clock));
    }

    /**
     * 회전(rotation) — 재발급 때마다 리프레시 토큰도 새로 발급하고 기존 것은 폐기한다.
     * 탈취된 토큰의 유효 기간을 "다음 재발급 시점까지"로 좁힌다.
     *
     * <p><b>판정 순서에 주의</b> — 만료를 <b>재사용 감지보다 먼저</b> 본다.
     * 회전된 토큰은 시간이 지나면 "폐기됨 + 만료됨" 상태가 되는데, 재사용을 먼저 판정하면
     * 앱 저장소에 남아 있던 오래된 토큰 하나 때문에 <b>전체 세션이 끊긴다.</b>
     * 만료된 토큰은 어차피 쓸모가 없으므로 이 순서로 잃는 방어력은 없고,
     * 재사용 감지는 "폐기됐지만 아직 유효한" 진짜 위험 구간에만 발동한다.
     * (스펙 S-3 의사코드는 재사용을 먼저 두고 있으나 위 이유로 뒤집었다.)
     */
    @Transactional
    public TokenResponse reissue(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHasher.hash(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (stored.isExpired(now)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        if (stored.isRevoked() && !stored.isWithinReuseGrace(now, jwtProperties.refreshReuseGrace())) {
            // 공격자와 정상 사용자 중 한쪽이 탈취된 토큰을 쓰고 있다는 뜻이다.
            // 전체 세션을 끊어 강제 재로그인시킨다 — 별도 트랜잭션이라 아래 예외로 롤백되지 않는다.
            refreshTokenRepository.revokeAllByUserId(stored.getUser().getId(), now, RevokedReason.REUSE_DETECTED);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
        }

        // 유예 창 안이면 이미 ROTATED로 폐기돼 있고, revoke()는 멱등이라 최초 시각/사유를 덮어쓰지 않는다.
        stored.revoke(now, RevokedReason.ROTATED);
        return issueTokens(stored.getUser(), now);
    }

    /**
     * 전달받은 리프레시 토큰을 폐기한다. Access Token은 무효화하지 않는다 —
     * 즉시 무효화하려면 블랙리스트 저장소가 필요해 무상태 이점이 사라지고,
     * TTL 30분이면 잔여 노출 시간이 짧아 수용 가능하다.
     *
     * <p><b>미존재는 멱등 처리</b>(이미 폐기된 토큰의 재전송이 정상 흐름이다).
     * 반면 <b>호출자와 소유자가 다르면 거부</b>한다 — 토큰 값을 아는 것만으로
     * 남의 세션을 끊을 수 있으면 그 자체가 공격 수단이 된다.
     */
    @Transactional
    public void logout(Long userId, String refreshToken) {
        refreshTokenRepository.findByTokenHash(tokenHasher.hash(refreshToken))
                .ifPresent(stored -> {
                    // LAZY 프록시의 식별자 접근이라 추가 쿼리가 나가지 않는다
                    if (!stored.getUser().getId().equals(userId)) {
                        throw new BusinessException(ErrorCode.ACCESS_DENIED);
                    }
                    stored.revoke(LocalDateTime.now(clock), RevokedReason.LOGOUT);
                });
    }

    private TokenResponse issueTokens(User user, LocalDateTime now) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken();

        refreshTokenRepository.save(RefreshToken.issue(
                user,
                tokenHasher.hash(refreshToken),
                now.plus(jwtProperties.refreshTokenTtl())));

        return new TokenResponse(accessToken, refreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }
}
