package com.project.cinemory.domain.user.controller;

import com.project.cinemory.domain.follow.dto.UserProfileResponse;
import com.project.cinemory.domain.follow.service.FollowService;
import com.project.cinemory.domain.user.dto.NicknameChangeRequest;
import com.project.cinemory.domain.user.dto.PasswordChangeRequest;
import com.project.cinemory.domain.user.dto.PrivacyChangeRequest;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getUserProfile}은 {@code FollowService}에 있어 두 Service를 함께 주입한다(5-1 설계 노트) —
 * 팔로우 수/여부가 프로필 헤더의 본질적 구성요소이고, {@code UserService}가
 * {@code FollowRepository}를 알게 되는 것보다 이 의존 방향이 자연스럽다는 4-6 결정을 그대로 따른다.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FollowService followService;

    /** 비공개 계정이어도 헤더 자체는 노출한다(4-6 확정) — 차단 대상은 시청기록/컬렉션/리뷰 등 콘텐츠다. */
    @Operation(summary = "사용자 프로필 헤더 조회")
    @GetMapping("/{userId}/profile")
    public ResponseEntity<UserProfileResponse> getUserProfile(@AuthUser Long viewerId,
                                                               @PathVariable Long userId) {
        return ResponseEntity.ok(followService.getUserProfile(viewerId, userId));
    }

    @Operation(summary = "내 정보 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    @Operation(summary = "닉네임 변경")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me/nickname")
    public ResponseEntity<UserResponse> changeNickname(@AuthUser(required = true) Long userId,
                                                        @Valid @RequestBody NicknameChangeRequest request) {
        userService.changeNickname(userId, request.nickname());
        return ResponseEntity.ok(userService.getUser(userId));
    }

    @Operation(summary = "공개범위 변경")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me/privacy")
    public ResponseEntity<UserResponse> changePrivacySetting(@AuthUser(required = true) Long userId,
                                                              @Valid @RequestBody PrivacyChangeRequest request) {
        userService.changePrivacySetting(userId, request.privacySetting());
        return ResponseEntity.ok(userService.getUser(userId));
    }

    /**
     * 성공(204) 이후 클라이언트는 저장된 토큰을 폐기하고 재로그인 화면으로 보내야 한다 —
     * 서버가 리프레시 토큰을 전부 끊었으므로 다음 재발급이 반드시 실패한다.
     */
    @Operation(summary = "비밀번호 변경")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthUser(required = true) Long userId,
                                               @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
