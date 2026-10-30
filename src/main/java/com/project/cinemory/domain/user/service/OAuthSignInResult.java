package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.entity.User;

/**
 * 소셜 로그인 결과. {@code created}는 이번 호출이 가입을 일으켰는가 — 앱이 가입 직후 한 번 닉네임 설정 화면을 띄우는 근거다(PR-10).
 * 서비스 간 호출 전용이라 엔티티를 담아도 DTO 원칙에 어긋나지 않는다({@code signUpOAuth}의 기존 판단과 같다).
 */
public record OAuthSignInResult(User user, boolean created) {
}
