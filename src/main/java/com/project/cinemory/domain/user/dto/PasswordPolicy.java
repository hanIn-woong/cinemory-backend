package com.project.cinemory.domain.user.dto;

/**
 * 비밀번호 형식 정책. 가입 · 재설정(S-J) · 변경(Step5 5-1)이 전부 이 상수를 공유한다 —
 * 검증 규칙이 갈리면 약한 쪽으로 정책을 우회할 수 있다.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    public static final String MESSAGE = "비밀번호는 8자 이상 64자 이하여야 합니다.";

    private PasswordPolicy() {
    }
}
