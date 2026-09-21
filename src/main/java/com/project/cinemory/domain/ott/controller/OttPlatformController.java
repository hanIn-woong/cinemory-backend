package com.project.cinemory.domain.ott.controller;

import com.project.cinemory.domain.ott.dto.OttPlatformResponse;
import com.project.cinemory.domain.ott.service.OttPlatformService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 잔여 #15(B-13) — {@code WatchRecordCreateRequest.ottPlatformId}(watch_type=OTT일 때 필수)를
 * 채울 유효한 ID를 얻을 방법이 없어 신설. 고정 길이 참조 목록이라 페이징하지 않는다
 * ({@code GET /api/theaters/nearby}와 같은 성격).
 */
@RestController
@RequiredArgsConstructor
public class OttPlatformController {

    private final OttPlatformService ottPlatformService;

    @Operation(summary = "OTT 플랫폼 목록 조회 (활성 항목만)")
    @GetMapping("/api/ott-platforms")
    public ResponseEntity<List<OttPlatformResponse>> getOttPlatforms() {
        return ResponseEntity.ok(ottPlatformService.getActivePlatforms());
    }
}
