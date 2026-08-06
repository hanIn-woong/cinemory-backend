package com.project.cinemory.global.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 불투명 토큰 원문 → SHA-256 hex 변환 전담.
 *
 * <p>DB에는 원문이 아니라 이 해시만 저장한다. 유출되더라도 그대로 재사용 가능한 값이
 * 남지 않게 하기 위함이다. <b>리프레시 토큰과 비밀번호 재설정 토큰이 함께 쓴다</b> —
 * 두 토큰의 요구사항(고엔트로피 랜덤값 / 해시 인덱스 조회 / 원문 미보관)이 동일해
 * v10에서 {@code RefreshTokenHasher}를 이 이름으로 일반화했다.
 *
 * <p><b>BCrypt를 쓰지 않는 이유</b> — 두 토큰 모두 이미 256bit 고엔트로피 랜덤값이라
 * 사전공격 대상이 아니고, 무엇보다 salt가 붙으면 <b>해시로 인덱스 조회를 할 수 없다.</b>
 * 비밀번호({@code PasswordEncoder})와는 요구사항이 다르다.
 *
 * <p>해시 계산은 JWT 발급({@code JwtTokenProvider})의 책임도, 인증 흐름 조율({@code AuthService})의
 * 책임도 아니라 별도 컴포넌트로 분리했다.
 */
@Component
public class TokenHasher {

    private static final String ALGORITHM = "SHA-256";

    /**
     * 결과는 소문자 hex 64자 — {@code refresh_token.token_hash} /
     * {@code password_reset_token.token_hash}의 길이와 일치한다.
     */
    public String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 반드시 제공한다. 여기 도달하면 런타임 자체가 비정상이다.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
