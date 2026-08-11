package com.project.cinemory.domain.boxoffice.controller;

import com.project.cinemory.domain.boxoffice.dto.BoxOfficeRematchResponse;
import com.project.cinemory.domain.boxoffice.dto.BoxOfficeSyncResponse;
import com.project.cinemory.domain.boxoffice.service.BoxOfficeSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다(5-6-B 확정) —
 * Controller에 {@code @PreAuthorize}를 중복으로 달지 않는다. 스케줄러({@link
 * com.project.cinemory.domain.boxoffice.scheduler.BoxOfficeScheduler})와 동일한
 * Service 메서드를 호출해 관리자용 별도 배치 로직을 만들지 않는다.
 *
 * <p>{@code domain/admin}이 아니라 {@code domain/boxoffice/controller}에 두는 이유(5-6-C ③) —
 * 패키지는 경로가 아니라 호출하는 Service가 소유한다는 기준(5-1의 {@code UserController} ←
 * {@code FollowService}와 같은 원칙). 극장 시드가 붙으면 {@code domain/theater/controller}에
 * 두 번째 Admin 컨트롤러가 생기는 것을 허용한다.
 *
 * <p>{@code targetDate}/{@code limit}은 {@code required = false}로 받아 {@code null}을 그대로
 * Service에 넘긴다 — 기본값 판단은 Controller가 아니라 Service 소관이다(5-6-A와 동일한 규칙).
 *
 * <p>{@code seedAll}(극장 시드)은 입력 방식(멀티파트 vs 서버 파일 경로)과 좌표계(WGS84 vs
 * EPSG:5174) 확인이 선행돼야 해 이번 단계에서는 엔드포인트를 두지 않는다(잔여 항목 #5).
 */
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final BoxOfficeSyncService boxOfficeSyncService;

    @Operation(summary = "박스오피스 수동 수집")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/box-office/sync")
    public ResponseEntity<BoxOfficeSyncResponse> syncBoxOffice(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        return ResponseEntity.ok(new BoxOfficeSyncResponse(boxOfficeSyncService.syncDaily(targetDate)));
    }

    @Operation(summary = "박스오피스 미매칭 레코드 재매칭")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/box-office/rematch")
    public ResponseEntity<BoxOfficeRematchResponse> rematchBoxOffice(
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(new BoxOfficeRematchResponse(boxOfficeSyncService.rematchUnlinked(limit)));
    }
}
