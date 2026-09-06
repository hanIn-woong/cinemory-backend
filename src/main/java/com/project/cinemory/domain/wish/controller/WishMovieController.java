package com.project.cinemory.domain.wish.controller;

import com.project.cinemory.domain.wish.dto.WishListItemResponse;
import com.project.cinemory.domain.wish.dto.WishToggleResponse;
import com.project.cinemory.domain.wish.service.WishMovieService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위시 토글은 생성이 아니라 상태 전이이므로 200이다 — 같은 엔드포인트가 추가/삭제를 모두
 * 수행하고 {@code wished} 플래그가 응답의 본체다(4-6 확정). {@code isWished}를
 * {@code /api/wishes/me/**}로 뺀 근거는 {@code ReviewController.getMyReview}와 동일하다(5-0-F).
 */
@RestController
@RequiredArgsConstructor
public class WishMovieController {

    private final WishMovieService wishMovieService;

    @Operation(summary = "사용자 위시리스트 조회")
    @GetMapping("/api/users/{userId}/wishes")
    public ResponseEntity<PageResponse<WishListItemResponse>> getUserWishList(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(wishMovieService.getUserWishList(viewerId, userId, pageable)));
    }

    @Operation(summary = "위시 토글(찜/찜 해제)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/movies/{movieId}/wish")
    public ResponseEntity<WishToggleResponse> toggleWish(@AuthUser(required = true) Long userId,
                                                          @PathVariable Long movieId) {
        return ResponseEntity.ok(wishMovieService.toggleWish(userId, movieId));
    }

    @Operation(summary = "내 위시 여부 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/wishes/me/{movieId}")
    public ResponseEntity<WishToggleResponse> isWished(@AuthUser(required = true) Long userId,
                                                        @PathVariable Long movieId) {
        return ResponseEntity.ok(new WishToggleResponse(wishMovieService.isWished(userId, movieId)));
    }
}
