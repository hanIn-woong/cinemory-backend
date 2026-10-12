package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.service.OAuthVerificationService;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.domain.user.dto.SocialAccountResponse;
import com.project.cinemory.domain.user.dto.SocialAccountsResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.repository.UserSocialAccountRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 로그인한 사용자의 소셜 계정 연결·조회·해제 (account-integrity S-5·S-6·S-8, D-2-A).
 *
 * <p><b>연결과 해제는 {@code user} 행을 비관적 락으로 먼저 잡는다.</b> "인증 수단 최소 1개"는 테이블을 넘나드는
 * 서비스 불변식이라 CHECK로 막을 수 없고, 두 기기에서 동시에 서로 다른 제공자를 해제하면 각자 "2개 남음"을 보고
 * 둘 다 지울 수 있다. <b>개수는 잠금 읽기로 센다 — 스냅샷 순서와 무관</b>(account-integrity D-4 #3): 잠금 읽기는 항상 최신
 * 커밋 값을 읽으므로, 락 앞에 일반 조회가 끼어도 동시 해제가 지운 행을 놓치지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialAccountService {

    private final UserRepository userRepository;
    private final UserSocialAccountRepository userSocialAccountRepository;
    private final OAuthVerificationService oauthVerificationService;

    public SocialAccountsResponse getSocialAccounts(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<SocialAccountResponse> accounts = userSocialAccountRepository
                .findAllByUserIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(SocialAccountResponse::from)
                .toList();
        return new SocialAccountsResponse(user.hasPassword(), accounts);
    }

    /**
     * 연결. ① 검증(로그인과 같은 관문 — nonce 소비 → ID 토큰) ② 남의 계정에 연결돼 있으면 {@code SOCIAL_ACCOUNT_ALREADY_LINKED}
     * ③ 내게 같은 제공자가 있으면 {@code SOCIAL_PROVIDER_ALREADY_LINKED} ④ 저장.
     *
     * <p>검증을 락보다 먼저 한다 — 외부 JWKS 조회가 낄 수 있는 구간에 행 락을 들고 있지 않기 위해서다(검증은 DB를 읽지 않는다).
     * 제공자의 이메일이 {@code user.email}과 달라도 허용한다 — 대표 이메일은 가입 시의 것을 유지한다(S-5).
     * 서로 다른 사용자가 같은 소셜 계정을 동시에 연결하는 경합은 {@code uk_user_social_account_provider}가 막는다(409 {@code DUPLICATE_REQUEST}).
     */
    @Transactional
    public void link(Long userId, String providerName, String idToken, String nonce) {
        OAuthProvider provider = OAuthProvider.from(providerName);
        OAuthUserInfo userInfo = oauthVerificationService.verify(provider, idToken, nonce);

        User user = findUserForUpdateOrThrow(userId);

        Optional<User> owner = userSocialAccountRepository.findUserByProviderAndProviderId(provider, userInfo.providerId());
        if (owner.isPresent() && !owner.get().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        }
        // 같은 소셜 계정을 다시 연결하는 경우(owner == 나)도 여기서 걸린다 — 이미 그 제공자가 연결돼 있으므로
        if (userSocialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
        }

        userSocialAccountRepository.save(UserSocialAccount.link(user, provider, userInfo.providerId()));
    }

    /**
     * 해제. 인증 수단 = 비밀번호(로컬 가입자만) + 연결된 소셜들(S-6) — 합이 1이면 {@code LAST_AUTH_METHOD}.
     * 연결 안 된 제공자는 {@code SOCIAL_ACCOUNT_NOT_FOUND}가 먼저다.
     *
     * <p>연결 목록을 잠금 읽기로 한 번 가져와 대상 찾기와 개수 세기를 함께 한다. {@code hasPassword()}는
     * 이미 잠근 {@code user} 행에서 읽으므로 최신이다.
     */
    @Transactional
    public void unlink(Long userId, String providerName) {
        OAuthProvider provider = OAuthProvider.from(providerName);
        User user = findUserForUpdateOrThrow(userId);

        List<UserSocialAccount> linked = userSocialAccountRepository.findAllByUserIdForUpdate(userId);
        UserSocialAccount account = linked.stream()
                .filter(socialAccount -> socialAccount.getProvider() == provider)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_ACCOUNT_NOT_FOUND));

        long authMethods = linked.size() + (user.hasPassword() ? 1 : 0);
        if (authMethods <= 1) {
            throw new BusinessException(ErrorCode.LAST_AUTH_METHOD);
        }

        userSocialAccountRepository.delete(account);
    }

    private User findUserForUpdateOrThrow(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
