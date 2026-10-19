package com.project.cinemory.domain.admin.controller;

import com.project.cinemory.domain.admin.dto.AdminAuditLogResponse;
import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditQueryService;
import com.project.cinemory.global.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 관리자 감사 로그 조회(admin-spec 11-4). 인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다.
 * 정렬 파라미터는 없다 — 최신순 고정.
 */
@RestController
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminAuditQueryService adminAuditQueryService;

    @Operation(summary = "관리자 감사 로그 — 동작·대상·관리자·기간 필터, 최신순 고정 (page 0-based)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/audit-logs")
    public ResponseEntity<PageResponse<AdminAuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) AdminAction action,
            @RequestParam(required = false) AdminTargetType targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminAuditQueryService.getLogs(
                action, targetType, targetId, adminUserId, from, to, pageable.getPageNumber(), pageable.getPageSize()));
    }
}
