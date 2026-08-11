package com.project.cinemory.domain.boxoffice.controller;

import com.project.cinemory.domain.boxoffice.dto.BoxOfficeResponse;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.boxoffice.service.BoxOfficeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 박스오피스는 공용 데이터라 {@code viewerId}를 받지 않는다(4-7 확정). */
@RestController
@RequiredArgsConstructor
public class BoxOfficeController {

    private final BoxOfficeQueryService boxOfficeQueryService;

    /** {@code targetDate}가 없으면 Service가 해당 구분의 최신 집계일로 대체한다(4-7 확정). */
    @Operation(summary = "박스오피스 순위 조회")
    @GetMapping("/api/box-office")
    public ResponseEntity<BoxOfficeResponse> getBoxOffice(
            @RequestParam RankType rankType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        return ResponseEntity.ok(boxOfficeQueryService.getBoxOffice(rankType, targetDate));
    }
}
