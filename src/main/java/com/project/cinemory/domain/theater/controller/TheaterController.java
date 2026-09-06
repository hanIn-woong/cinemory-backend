package com.project.cinemory.domain.theater.controller;

import com.project.cinemory.domain.theater.dto.TheaterResponse;
import com.project.cinemory.domain.theater.service.TheaterQueryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 극장은 사용자 소유 데이터가 아닌 공용 데이터라 {@code viewerId}를 받지 않는다(4-7 확정).
 * {@code radiusMeters}/{@code limit}은 {@code null}을 그대로 Service에 넘긴다 — 기본값 적용과
 * 상한 검증({@code INVALID_SEARCH_RADIUS})을 Service 한 곳에서 처리한다(2026-08-10 개정, 5-6-C ②).
 * Controller가 {@code @RequestParam(defaultValue = "${cinemory.…}")}로 직접 끌어오지 않는 이유는
 * 플레이스홀더가 요청 시점에 해석돼 키 오타가 있어도 기동은 통과하고 첫 호출에서만 500이 나기
 * 때문이다 — 값에 대한 판단은 Controller가 아니라 Service 소관이라는 5-0-B 원칙과도 일치한다.
 */
@RestController
@RequiredArgsConstructor
public class TheaterController {

    private final TheaterQueryService theaterQueryService;

    @Operation(summary = "주변 극장 조회")
    @GetMapping("/api/theaters/nearby")
    public ResponseEntity<List<TheaterResponse>> getNearbyTheaters(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(required = false) Integer radiusMeters,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(theaterQueryService.getNearbyTheaters(latitude, longitude, radiusMeters, limit));
    }
}
