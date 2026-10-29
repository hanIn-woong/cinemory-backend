package com.project.cinemory.domain.feed.controller;

import com.project.cinemory.domain.feed.dto.ActivityPageResponse;
import com.project.cinemory.domain.feed.dto.FeedScope;
import com.project.cinemory.domain.feed.dto.FeedStripsResponse;
import com.project.cinemory.domain.feed.service.FeedService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 소셜 피드 (social-feed-spec 3-1 · 3-2). 두 API 모두 인증 필수(SF-8). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FeedController {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Seoul");

    private final FeedService feedService;

    @Operation(summary = "팔로잉 스트립 목록 조회 (필름 스트립)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/feed/strips")
    public ResponseEntity<FeedStripsResponse> getStrips(
            @AuthUser(required = true) Long viewerId,
            @RequestParam(defaultValue = "FOLLOWING") FeedScope scope,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String seenAfter) {
        return ResponseEntity.ok(feedService.getStrips(viewerId, scope, cursor, size, parseSeenAfter(seenAfter)));
    }

    /** {@code /api/users/**} GET 중 일부가 permitAll이므로 인증 강제는 {@code required = true}로 한다(팔로우 API와 같은 방식). */
    @Operation(summary = "개인 활동 피드 조회 (티켓 피드)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/{userId}/activities")
    public ResponseEntity<ActivityPageResponse> getActivities(
            @AuthUser(required = true) Long viewerId,
            @PathVariable Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(feedService.getActivities(viewerId, userId, cursor, size));
    }

    /**
     * ISO-8601. 오프셋({@code Z} 포함)이 붙어 있으면 서버 시간대로 옮긴다 — JS의 {@code toISOString()}은 항상 UTC({@code Z})다.
     * 오프셋이 없으면 서버 시간대의 로컬 시각으로 본다.
     */
    private static LocalDateTime parseSeenAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(SERVER_ZONE).toLocalDateTime();
        } catch (DateTimeException offsetFailed) {
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
            }
        }
    }
}
