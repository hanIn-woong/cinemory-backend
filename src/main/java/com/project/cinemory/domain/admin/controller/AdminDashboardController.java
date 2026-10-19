package com.project.cinemory.domain.admin.controller;

import com.project.cinemory.domain.admin.dto.AdminDashboardResponse;
import com.project.cinemory.domain.admin.service.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 대시보드(admin-spec 13-1). 인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다.
 * 개인정보 없는 집계라 감사 로그에 남기지 않는다. {@code days}는 7~90(기본 30), 범위 밖이면 Service가 400.
 */
@RestController
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @Operation(summary = "관리자 대시보드 — 누적·최근 7일 KPI, 일별 추이(빈 날 0), 기록 TOP 10 영화 (days 7~90, 날짜는 KST)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/dashboard")
    public ResponseEntity<AdminDashboardResponse> getDashboard(
            @RequestParam(defaultValue = "" + AdminDashboardService.DEFAULT_DAYS) int days) {
        return ResponseEntity.ok(adminDashboardService.getDashboard(days));
    }
}
