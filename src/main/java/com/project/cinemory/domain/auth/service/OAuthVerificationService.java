package com.project.cinemory.domain.auth.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.service.oauth.OAuthIdTokenVerifier;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 소셜 ID 토큰 검증 — <b>로그인({@code AuthService})과 계정 연결({@code SocialAccountService})의 공통 관문</b>이다.
 * S-5 "연결은 로그인과 같은 검증기(ID 토큰 + nonce)를 거친다"를 구조로 보장하려고 둘에서 꺼내 한 곳에 두었다.
 * 연결이 별도 검증 경로를 가지면 nonce 순서 같은 방어가 한쪽에서만 빠지기 쉽다.
 */
@Service
public class OAuthVerificationService {

    private final OAuthNonceService nonceService;
    private final Map<OAuthProvider, OAuthIdTokenVerifier> verifiers;

    /**
     * {@code Map<OAuthProvider, OAuthIdTokenVerifier>}를 직접 주입받지 않는 이유:
     * Spring의 Map 자동 주입은 키가 <b>빈 이름(String)</b>이라 enum 키로 쓸 수 없다.
     * List로 받아 생성자에서 변환하면 필드를 final로 유지할 수 있다 (4-6 {@code CommentService}와 동일).
     */
    public OAuthVerificationService(OAuthNonceService nonceService, List<OAuthIdTokenVerifier> verifiers) {
        this.nonceService = nonceService;
        this.verifiers = new EnumMap<>(OAuthProvider.class);
        verifiers.forEach(verifier -> this.verifiers.put(verifier.supports(), verifier));
    }

    /**
     * <b>판정 순서: 검증기 조회 → nonce 소비 → ID 토큰 검증.</b>
     * <ul>
     *   <li>검증기 조회가 먼저 — 지원하지 않는 provider로 온 요청이 정상 발급된 nonce를 태우지 않게 한다.</li>
     *   <li>nonce 소비가 검증보다 먼저 — 순서를 바꾸면 검증이 실패할 때 nonce가 캐시에 남아
     *       <b>같은 nonce로 토큰만 바꿔가며 반복 시도</b>할 수 있다. 소비를 먼저 하면 시도 1회당 nonce 1개가 강제된다.</li>
     * </ul>
     *
     * @throws BusinessException {@code UNSUPPORTED_OAUTH_PROVIDER} / {@code INVALID_NONCE} /
     *                           {@code INVALID_OAUTH_TOKEN} / {@code OAUTH_EMAIL_NOT_PROVIDED}
     */
    public OAuthUserInfo verify(OAuthProvider provider, String idToken, String nonce) {
        OAuthIdTokenVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            // enum에는 있으나 구현체가 없는 경우 — 값을 먼저 추가하고 구현을 잊었을 때 여기로 온다
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }

        nonceService.consumeOrThrow(nonce);
        return verifier.verify(idToken, nonce);
    }
}
