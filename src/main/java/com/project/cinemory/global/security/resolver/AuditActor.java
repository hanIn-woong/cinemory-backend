package com.project.cinemory.global.security.resolver;

/**
 * 감사 로그에 남길 행위자(admin-spec 11-3) — {@link CurrentAdmin}이 붙은 Controller 파라미터로 주입된다.
 *
 * @param adminUserId 인증된 관리자 사용자 ID
 * @param ip          요청 IP. Nginx의 {@code X-Forwarded-For} + {@code forward-headers-strategy: native}로 실제 클라이언트 IP가 들어온다
 */
public record AuditActor(Long adminUserId, String ip) {
}
