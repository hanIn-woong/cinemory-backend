package com.project.cinemory.global.infra.kakao.dto;

import java.util.List;

/**
 * JWKS(JSON Web Key Set) 응답.
 *
 * <pre>
 * { "keys": [ { "kid": "...", "kty": "RSA", "alg": "RS256", "use": "sig",
 *               "n": "&lt;Base64URL&gt;", "e": "AQAB" } ] }
 * </pre>
 *
 * <p>표준에 정의된 필드는 더 많지만 서명 검증에 필요한 것만 받는다.
 * 모르는 필드는 Jackson이 무시하므로 카카오가 항목을 늘려도 깨지지 않는다.
 */
public record JwkSetResponse(List<Jwk> keys) {

    /**
     * @param kid 키 식별자. ID 토큰 헤더의 {@code kid}와 대조한다
     * @param kty 키 타입. RSA만 사용한다
     * @param alg 서명 알고리즘 (RS256)
     * @param use 용도 (sig)
     * @param n   RSA 계수(modulus), Base64URL
     * @param e   RSA 공개 지수(exponent), Base64URL
     */
    public record Jwk(String kid, String kty, String alg, String use, String n, String e) {
    }
}
