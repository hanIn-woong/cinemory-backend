package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;

/**
 * Access Token에서 복원한 인증 주체. {@code JwtAuthenticationFilter}가 이 값으로
 * SecurityContext를 채우고, Controller는 {@code @AuthUser}로 {@code userId}만 받는다.
 *
 * <p>{@code role}이 토큰에 담겨 있어 인가 판정마다 DB를 읽지 않는다. 대신 권한이 바뀌어도
 * Access TTL만큼 반영이 지연되는데, 관리자 승격이 실시간일 필요가 없어 수용한 트레이드오프다.
 */
public record AuthUserPrincipal(Long userId, RoleType role) {
}
