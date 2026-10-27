package com.project.cinemory.domain.theater.controller;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.admin.service.AuditDetail;
import com.project.cinemory.domain.theater.dto.AdminTheaterBookingUrlRequest;
import com.project.cinemory.domain.theater.dto.AdminTheaterResponse;
import com.project.cinemory.domain.theater.dto.TheaterSeedReport;
import com.project.cinemory.domain.theater.service.AdminTheaterCommandService;
import com.project.cinemory.domain.theater.service.AdminTheaterQueryService;
import com.project.cinemory.domain.theater.service.TheaterSeedService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuditActor;
import com.project.cinemory.global.security.resolver.CurrentAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AdminTheaterQueryService adminTheaterQueryService;
    private final AdminTheaterCommandService adminTheaterCommandService;

    @Operation(summary = "관리자 극장 목록 — 이름·주소 검색, 체인·예매 링크 유무·폐업 필터. 숨긴 극장도 포함 (page 0-based)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/theaters")
    public ResponseEntity<PageResponse<AdminTheaterResponse>> getTheaters(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) Boolean hasBookingUrl,
            @RequestParam(required = false) Boolean closed,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminTheaterQueryService.getTheaters(
                query, chain, hasBookingUrl, closed, pageable.getPageNumber(), pageable.getPageSize()));
    }

    /** 감사 로그(THEATER_BOOKING_URL_UPDATE)는 서비스가 같은 트랜잭션에서 남긴다. */
    @Operation(summary = "극장 예매 링크 변경 — null은 삭제, https:// 시작·500자 이하·공백 불가(위반 400 INVALID_BOOKING_URL), 같은 값이면 기록 없이 200")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/api/admin/theaters/{theaterId}/booking-url")
    public ResponseEntity<AdminTheaterResponse> changeBookingUrl(@PathVariable Long theaterId,
                                                                 @RequestBody AdminTheaterBookingUrlRequest request,
                                                                 @CurrentAdmin AuditActor actor) {
        return ResponseEntity.ok(adminTheaterCommandService.changeBookingUrl(theaterId, request.bookingUrl(), actor));
    }

    @Operation(summary = "극장 시드·동기화 — 공공데이터 영화상영관 적재, 폐업 숨김·복구. dryRun=true면 저장 없이 통계만 (감사 로그 THEATER_SEED는 dryRun=false이고 중단되지 않았을 때만)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/theaters/seed")
    public ResponseEntity<TheaterSeedReport> seed(@RequestParam(defaultValue = "false") boolean dryRun,
                                                  @RequestParam(defaultValue = "false") boolean allowMassClose,
                                                  @CurrentAdmin AuditActor actor) {
        TheaterSeedReport report = theaterSeedService.seed(dryRun, allowMassClose);
        // 중단된 실행(C-5-2 ①)은 아무것도 반영하지 않았으므로 남기지 않는다 — 성공한 동작만 기록한다
        if (!dryRun && !report.aborted()) {
            // 외부 호출이 트랜잭션 밖이라 저장이 끝난 뒤 남긴다(admin-spec 11-3)
            adminAuditService.record(actor, AdminAction.THEATER_SEED, null, null, AuditDetail.of(
                    "allowMassClose", allowMassClose ? true : null,
                    "theaterCount", report.theaterCount(), "new", report.newTheaters(), "closed", report.closedTheaters(),
                    "reopened", report.reopenedTheaters(), "massCloseHeld", report.massCloseHeld() > 0 ? report.massCloseHeld() : null,
                    "coordinateFailed", report.coordinates().failed()));
        }
        return ResponseEntity.ok(report);
    }
}
