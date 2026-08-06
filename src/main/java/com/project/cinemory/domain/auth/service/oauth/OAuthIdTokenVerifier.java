package com.project.cinemory.domain.auth.service.oauth;

import com.project.cinemory.domain.auth.entity.OAuthProvider;

/**
 * provider별 ID 토큰 검증 전략. 4-6 {@code CommentTargetResolver}와 같은 패턴이다.
 */
public interface OAuthIdTokenVerifier {

    OAuthProvider supports();

    /**
     * 서명과 클레임을 검증하고 사용자 정보를 뽑아낸다.
     *
     * @param expectedNonce 이 로그인 시도를 위해 우리가 발급했던 nonce.
     *                      토큰의 {@code nonce} 클레임과 일치해야 한다
     * @throws com.project.cinemory.global.exception.BusinessException
     *         {@code INVALID_OAUTH_TOKEN}(서명·발급자·대상·만료) /
     *         {@code INVALID_NONCE}(nonce 불일치) /
     *         {@code OAUTH_EMAIL_NOT_PROVIDED}(이메일 미제공)
     */
    OAuthUserInfo verify(String idToken, String expectedNonce);
}
