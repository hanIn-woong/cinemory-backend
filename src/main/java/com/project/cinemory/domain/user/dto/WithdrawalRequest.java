package com.project.cinemory.domain.user.dto;

/**
 * 비밀번호가 없는 계정(소셜 전용)은 생략할 수 있어 {@code @NotBlank}를 두지 않는다.
 * 비밀번호가 있는 계정에서 비어 있으면 서비스가 {@code INVALID_CREDENTIALS}로 거부한다.
 */
public record WithdrawalRequest(String password) {
}
