package com.project.cinemory.domain.report.controller;

import com.project.cinemory.domain.report.dto.ReportCalendarResponse;
import com.project.cinemory.domain.report.dto.ReportMonthlyResponse;
import com.project.cinemory.domain.report.dto.ReportStatisticsResponse;
import com.project.cinemory.domain.report.service.ReportService;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3-a 시청 분석 리포트(5-8). 화면당 엔드포인트 하나(A안) — 캘린더가 월을 넘길 때마다 통계
 * 전체를 다시 계산하지 않기 위해서다. {@code /api/users/{userId}/…} 경로를 쓰지만
 * {@code UserController}가 아니라 여기 둔다 — 경로 접두사와 패키지 소속은 별개다(5-8-E).
 *
 * <p>셋 다 {@code viewerId}는 nullable(비로그인 PUBLIC 조회 허용)이며 {@code UserAccessPolicy}로
 * 가시성을 판정한다(RA-6). {@code PageResponse}는 쓰지 않는다 — TOP N은 페이징이 아니다(5-8-C).
 */
@RestController
@RequestMapping("/api/users/{userId}/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "누적 시청 통계 조회",
            description = "movieCount(고유 편수)와 watchCount(전 회차)는 다른 지표다. monthlyTrend는 watch_date가 있는 기록만 담는다.")
    @GetMapping("/statistics")
    public ResponseEntity<ReportStatisticsResponse> getStatistics(
            @AuthUser Long viewerId,
            @PathVariable Long userId) {
        return ResponseEntity.ok(reportService.getStatistics(viewerId, userId));
    }

    @Operation(summary = "월말 리포트 조회",
            description = "year/month는 필수이며 서버 기본값이 없다. 미래 월은 빈 결과 200으로 응답한다.")
    @GetMapping("/monthly")
    public ResponseEntity<ReportMonthlyResponse> getMonthlyReport(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(reportService.getMonthlyReport(viewerId, userId, year, month));
    }

    @Operation(summary = "월별 캘린더 조회",
            description = "하루에 여러 편이 가능해 days[].records는 배열이다. year/month는 필수이며 서버 기본값이 없다.")
    @GetMapping("/calendar")
    public ResponseEntity<ReportCalendarResponse> getCalendar(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(reportService.getCalendar(viewerId, userId, year, month));
    }
}
