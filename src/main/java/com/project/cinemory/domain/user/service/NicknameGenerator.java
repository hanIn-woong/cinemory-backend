package com.project.cinemory.domain.user.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 소셜 가입자의 자동 닉네임 (profile-spec PR-10) — {@code 관객} + 6자리 숫자(100000~999999).
 * 닉네임 중복을 허용하므로(PR-7) 중복 검사는 하지 않는다. 생성값은 {@link NicknamePolicy}를 통과한다(테스트로 고정).
 */
@Component
public class NicknameGenerator {

    static final String PREFIX = "관객";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return PREFIX + (100_000 + random.nextInt(900_000));
    }
}
