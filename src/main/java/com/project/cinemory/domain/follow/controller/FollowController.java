package com.project.cinemory.domain.follow.controller;

import com.project.cinemory.domain.follow.dto.FollowUserResponse;
import com.project.cinemory.domain.follow.service.FollowService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 여기서 {@code {userId}}는 소유자가 아니라 <b>대상</b>이므로 5-0-F의
 * "쓰기 경로에 주체를 넣지 않는다"에 위배되지 않는다(follower는 {@code @AuthUser}로 받는다).
 */
@RestController
@RequestMapping("/api/users/{userId}")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** 멱등 — 이미 팔로우 중이어도 204. 클라이언트가 사전 상태 조회를 하지 않아도 된다(4-6 확정). */
    @Operation(summary = "팔로우")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/follow")
    public ResponseEntity<Void> follow(@AuthUser(required = true) Long followerId,
                                       @PathVariable Long userId) {
        followService.follow(followerId, userId);
        return ResponseEntity.noContent().build();
    }

    /** 멱등 — 팔로우 중이 아니어도 204. */
    @Operation(summary = "언팔로우")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/follow")
    public ResponseEntity<Void> unfollow(@AuthUser(required = true) Long followerId,
                                         @PathVariable Long userId) {
        followService.unfollow(followerId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "팔로워 목록 조회")
    @GetMapping("/followers")
    public ResponseEntity<PageResponse<FollowUserResponse>> getFollowers(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(followService.getFollowers(viewerId, userId, pageable)));
    }

    @Operation(summary = "팔로잉 목록 조회")
    @GetMapping("/followings")
    public ResponseEntity<PageResponse<FollowUserResponse>> getFollowings(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(followService.getFollowings(viewerId, userId, pageable)));
    }
}
