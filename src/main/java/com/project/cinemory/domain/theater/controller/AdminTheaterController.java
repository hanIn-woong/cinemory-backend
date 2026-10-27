package com.project.cinemory.domain.theater.controller;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.admin.service.AuditDetail;
import com.project.cinemory.domain.theater.dto.TheaterSeedReport;
import com.project.cinemory.domain.theater.service.TheaterSeedService;
import com.project.cinemory.global.security.resolver.AuditActor;
import com.project.cinemory.global.security.resolver.CurrentAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 극장 시드 관리자 엔드포인트(cinemap-spec C-4). 인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다.
 * 빈 이름은 다른 {@code AdminController}들과 충돌하지 않게 명시한다.
 */
@RestController("theaterAdminController")
@RequiredArgsConstructor
public class AdminTheaterController {

    private final TheaterSeedService theaterSeedService;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "극장 시드 — 공공데이터 영화상영관 적재. dryRun=true면 저장 없이 통계만 (감사 로그 THEATER_SEED는 dryRun=false일 때만)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/theaters/seed")
    public ResponseEntity<TheaterSeedReport> seed(@RequestParam(defaultValue = "false") boolean dryRun,
                                                  @CurrentAdmin AuditActor actor) {
        TheaterSeedReport report = theaterSeedService.seed(dryRun);
        if (!dryRun) {
            // 외부 호출이 트랜잭션 밖이라 저장이 끝난 뒤 남긴다(admin-spec 11-3)
            adminAuditService.record(actor, AdminAction.THEATER_SEED, null, null, AuditDetail.of(
                    "theaterCount", report.theaterCount(), "inserted", report.inserted(), "updated", report.updated(),
                    "coordinateFailed", report.coordinates().failed()));
        }
        return ResponseEntity.ok(report);
    }
}
