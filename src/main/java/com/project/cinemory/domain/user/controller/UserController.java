package com.project.cinemory.domain.user.controller;

import com.project.cinemory.domain.follow.dto.FollowUserResponse;
import com.project.cinemory.domain.follow.dto.UserProfileResponse;
import com.project.cinemory.domain.follow.service.FollowService;
import com.project.cinemory.domain.user.dto.NicknameChangeRequest;
import com.project.cinemory.domain.user.dto.PasswordChangeRequest;
import com.project.cinemory.domain.user.dto.PrivacyChangeRequest;
import com.project.cinemory.domain.user.dto.ProfileUpdateRequest;
import com.project.cinemory.domain.user.dto.ProfileImageConfirmRequest;
import com.project.cinemory.domain.user.dto.ProfileImageUploadUrlResponse;
import com.project.cinemory.domain.user.dto.SocialAccountsResponse;
import com.project.cinemory.domain.user.dto.SocialLinkRequest;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.dto.WithdrawalRequest;
import com.project.cinemory.domain.user.service.CoverImageService;
import com.project.cinemory.domain.user.service.ProfileImageService;
import com.project.cinemory.domain.user.service.ProfileService;
import com.project.cinemory.domain.user.service.SocialAccountService;
import com.project.cinemory.domain.user.service.UserService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final SocialAccountService socialAccountService;
    private final ProfileImageService profileImageService;
    private final CoverImageService coverImageService;
    private final ProfileService profileService;

    /** 비공개 계정이어도 헤더 자체는 노출한다(4-6 확정) — 차단 대상은 시청기록/컬렉션/리뷰 등 콘텐츠다. */
    @Operation(summary = "사용자 프로필 헤더 조회")
    @GetMapping("/{userId}/profile")
    public ResponseEntity<UserProfileResponse> getUserProfile(@AuthUser Long viewerId,
                                                               @PathVariable Long userId) {
        return ResponseEntity.ok(followService.getUserProfile(viewerId, userId));
    }

    /**
     * 이 경로는 PUBLIC_GET_ENDPOINTS에 없어 기본 {@code authenticated()}지만, 팔로우 API와 같은 방식으로
     * 컨트롤러에서도 한 번 더 막는다(social-feed-spec 10-2).
     */
    @Operation(summary = "사용자 검색 (닉네임 부분 일치, 2~20자)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/search")
    public ResponseEntity<PageResponse<FollowUserResponse>> searchUsers(
            @AuthUser(required = true) Long viewerId,
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(followService.searchUsers(viewerId, q, pageable)));
    }

    @Operation(summary = "내 정보 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    /** 소셜 가입은 가입 순간에 화면이 없어 닉네임 설정 화면(PA-2)이 "시작하기"에서 호출한다. 멱등(settings-spec ST-7). */
    @Operation(summary = "가입 동의 기록 (멱등)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/terms-agreement")
    public ResponseEntity<Void> agreeToTerms(@AuthUser(required = true) Long userId) {
        userService.agreeToTerms(userId);
        return ResponseEntity.noContent().build();
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

    @Operation(summary = "프로필 통합 저장 (전체 치환)",
            description = "닉네임·자기소개·공개 범위·인생 영화(0~3편)·영화 커버를 한 번에 저장한다. 이미지(사진·업로드 커버)는 별도 업로드 흐름. "
                    + "coverMovieId가 null이면 영화 커버만 해제하고 업로드 커버는 유지한다.")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/me/profile")
    public ResponseEntity<UserResponse> updateProfile(@AuthUser(required = true) Long userId,
                                                      @Valid @RequestBody ProfileUpdateRequest request) {
        return ResponseEntity.ok(profileService.updateProfile(userId, request));
    }

    @Operation(summary = "커버 이미지 업로드 URL 발급",
            description = "반환된 uploadUrl로 Content-Type: image/jpeg PUT 후 key를 PUT /api/users/me/cover-image에 넘긴다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/cover-image/upload-url")
    public ResponseEntity<ProfileImageUploadUrlResponse> issueCoverImageUploadUrl(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(coverImageService.issueUploadUrl(userId));
    }

    @Operation(summary = "커버 이미지 확정", description = "키 소유·존재·크기(2MB)·image/jpeg 검사. 실패 시 INVALID_COVER_IMAGE. 영화 커버는 해제된다.")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/me/cover-image")
    public ResponseEntity<UserResponse> confirmCoverImage(@AuthUser(required = true) Long userId,
                                                          @Valid @RequestBody ProfileImageConfirmRequest request) {
        return ResponseEntity.ok(coverImageService.confirm(userId, request.key()));
    }

    @Operation(summary = "업로드 커버 해제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/me/cover-image")
    public ResponseEntity<UserResponse> removeCoverImage(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(coverImageService.remove(userId));
    }

    @Operation(summary = "프로필 사진 업로드 URL 발급",
            description = "반환된 uploadUrl로 Content-Type: image/jpeg PUT 후 key를 PUT /api/users/me/profile-image에 넘긴다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/profile-image/upload-url")
    public ResponseEntity<ProfileImageUploadUrlResponse> issueProfileImageUploadUrl(
            @AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(profileImageService.issueUploadUrl(userId));
    }

    @Operation(summary = "프로필 사진 확정", description = "키 소유·존재·크기(2MB)·image/jpeg 검사. 실패 시 INVALID_PROFILE_IMAGE.")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/me/profile-image")
    public ResponseEntity<UserResponse> confirmProfileImage(@AuthUser(required = true) Long userId,
                                                            @Valid @RequestBody ProfileImageConfirmRequest request) {
        return ResponseEntity.ok(profileImageService.confirm(userId, request.key()));
    }

    @Operation(summary = "프로필 사진 삭제 (기본 아바타로)")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/me/profile-image")
    public ResponseEntity<UserResponse> removeProfileImage(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(profileImageService.remove(userId));
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

    /**
     * {@code DELETE}가 아닌 {@code POST}인 이유 — 비밀번호를 본문에 실어야 하는데 {@code DELETE} 본문은 HTTP 의미상 정의되지 않는다.
     * 비밀번호가 없는 계정(소셜 전용)은 본문 없이 호출할 수 있다. 성공(204) 후 앱은 토큰을 지운다 — 리프레시 토큰은 이미 삭제됐다.
     */
    @Operation(summary = "회원 탈퇴")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/withdrawal")
    public ResponseEntity<Void> withdraw(@AuthUser(required = true) Long userId,
                                         @RequestBody(required = false) WithdrawalRequest request) {
        userService.withdraw(userId, request == null ? null : request.password());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "연결된 소셜 계정 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/social-accounts")
    public ResponseEntity<SocialAccountsResponse> getSocialAccounts(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(socialAccountService.getSocialAccounts(userId));
    }

    /** 로그인과 같은 순서다 — 앱은 {@code POST /api/auth/nonce}로 nonce를 먼저 받아 SDK 로그인에 넘긴다(S-5). */
    @Operation(summary = "소셜 계정 연결")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/social-accounts/{provider}")
    public ResponseEntity<Void> linkSocialAccount(@AuthUser(required = true) Long userId,
                                                  @PathVariable String provider,
                                                  @Valid @RequestBody SocialLinkRequest request) {
        socialAccountService.link(userId, provider, request.idToken(), request.nonce());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "소셜 계정 연결 해제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/me/social-accounts/{provider}")
    public ResponseEntity<Void> unlinkSocialAccount(@AuthUser(required = true) Long userId,
                                                    @PathVariable String provider) {
        socialAccountService.unlink(userId, provider);
        return ResponseEntity.noContent().build();
    }
}
