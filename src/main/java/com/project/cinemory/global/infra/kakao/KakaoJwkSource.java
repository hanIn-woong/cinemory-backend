package com.project.cinemory.global.infra.kakao;

import java.security.interfaces.RSAPublicKey;

/**
 * ID 토큰 헤더의 {@code kid}에 대응하는 공개키를 돌려준다.
 *
 * <p><b>인터페이스로 분리한 이유</b> — 테스트에서 <b>자체 생성한 RSA 키쌍</b>을 그대로 꽂기 위해서다.
 * 이렇게 해두면 HTTP 없이도 서명 위조 / {@code aud} 불일치 / {@code nonce} 불일치 / 만료 같은
 * 검증 분기를 전부 태울 수 있다. 실제 카카오 토큰으로는 정상 케이스밖에 만들 수 없다.
 */
public interface KakaoJwkSource {

    /**
     * @throws com.project.cinemory.global.exception.BusinessException
     *         {@code INVALID_OAUTH_TOKEN} — 해당 {@code kid}의 공개키를 찾을 수 없는 경우
     */
    RSAPublicKey findByKid(String kid);
}
