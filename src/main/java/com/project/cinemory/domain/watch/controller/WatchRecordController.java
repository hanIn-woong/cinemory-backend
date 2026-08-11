package com.project.cinemory.domain.watch.controller;

import com.project.cinemory.domain.watch.dto.UserMovieListItemResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * 조회는 {@code /api/users/{userId}/records/**}(소유자 스코프), 쓰기는 {@code /api/records/**}
 * (주체를 경로에 넣지 않음)로 갈린다(5-0-F) — 클래스 레벨 {@code @RequestMapping} 없이
 * 메서드마다 전체 경로를 명시한다.
 */
@RestController
@RequiredArgsConstructor
public class WatchRecordController {

    private final WatchRecordService watchRecordService;

    @Operation(summary = "사용자 대표 시청 기록(\"내 영화\") 목록 조회")
    @GetMapping("/api/users/{userId}/records")
    public ResponseEntity<PageResponse<UserMovieListItemResponse>> getUserMovieList(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(watchRecordService.getUserMovieList(viewerId, userId, pageable)));
    }

    @Operation(summary = "특정 영화의 회차별 시청 기록 조회")
    @GetMapping("/api/users/{userId}/records/movies/{movieId}")
    public ResponseEntity<List<WatchRecordResponse>> getWatchLog(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @PathVariable Long movieId) {
        return ResponseEntity.ok(watchRecordService.getWatchLog(viewerId, userId, movieId));
    }

    @Operation(summary = "시청 기록 등록")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/records")
    public ResponseEntity<WatchRecordResponse> addWatchRecord(
            @AuthUser(required = true) Long userId,
            @Valid @RequestBody WatchRecordCreateRequest request) {
        WatchRecordResponse response = watchRecordService.addWatchRecord(userId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{recordId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "시청 기록 삭제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/api/records/{recordId}")
    public ResponseEntity<Void> deleteWatchRecord(@AuthUser(required = true) Long userId,
                                                  @PathVariable Long recordId) {
        watchRecordService.deleteWatchRecord(userId, recordId);
        return ResponseEntity.noContent().build();
    }

    /** 멱등 — 이미 대표면 즉시 반환한다(4-3 확정). */
    @Operation(summary = "대표 시청 기록으로 지정")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/api/records/{recordId}/representative")
    public ResponseEntity<Void> setRepresentative(@AuthUser(required = true) Long userId,
                                                  @PathVariable Long recordId) {
        watchRecordService.setRepresentative(userId, recordId);
        return ResponseEntity.noContent().build();
    }
}
