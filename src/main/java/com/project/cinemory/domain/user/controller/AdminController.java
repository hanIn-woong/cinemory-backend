package com.project.cinemory.domain.user.controller;

import com.project.cinemory.domain.user.dto.AdminMeResponse;
import com.project.cinemory.domain.user.dto.AdminUserDetailResponse;
import com.project.cinemory.domain.user.dto.AdminUserSort;
import com.project.cinemory.domain.user.dto.AdminUserSummaryResponse;
import com.project.cinemory.domain.user.service.AdminUserQueryService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 웹용 회원 조회 엔드포인트(admin-spec 3-1~3-3).
 *
 * <p>인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다(5-6-B) — {@code @PreAuthorize}를
 * 중복으로 달지 않는다. 다른 도메인의 {@code AdminController}와 클래스명이 같아 빈 이름만 명시해 충돌을 피한다.
 */
@RestController("userAdminController")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserQueryService adminUserQueryService;

    @Operation(summary = "관리자 판정 — 일반 사용자는 403 (관리자 웹 로그인 직후 호출)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/me")
    public ResponseEntity<AdminMeResponse> getMe(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(adminUserQueryService.getMe(userId));
    }

    @Operation(summary = "관리자 회원 목록 — 이메일/닉네임 검색·정렬 (page 0-based, 공개 범위 무시)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/users")
    public ResponseEntity<PageResponse<AdminUserSummaryResponse>> getUsers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "CREATED_AT_DESC") AdminUserSort sort,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminUserQueryService.getUsers(
                q, sort, pageable.getPageNumber(), pageable.getPageSize()));
    }

    @Operation(summary = "관리자 회원 상세 — 활동 지표 8종 포함")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/users/{userId}")
    public ResponseEntity<AdminUserDetailResponse> getUser(@PathVariable Long userId) {
        return ResponseEntity.ok(adminUserQueryService.getUser(userId));
    }
}
