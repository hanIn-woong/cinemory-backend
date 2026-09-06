package com.project.cinemory.domain.review.controller;

import com.project.cinemory.domain.review.dto.ReviewResponse;
import com.project.cinemory.domain.review.dto.ReviewWriteRequest;
import com.project.cinemory.domain.review.service.ReviewService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조회는 {@code /api/movies/{movieId}/reviews}(공개), 쓰기는 {@code /api/movies/{movieId}/review}
 * 아래에 두되 화이트리스트가 <b>GET만</b> permitAll이라 PUT/DELETE는 안전하다(5-0-F).
 * {@code getMyReview}만 {@code /api/reviews/me}로 뺀다 — GET을 {@code /api/movies/**} 아래 두면
 * permitAll에 걸려 필터 보호가 사라지기 때문이다.
 */
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * {@code totalElements}에 비공개 작성자의 리뷰가 포함되고 페이지당 항목 수가 요청 size보다
     * 적을 수 있다(4-6-E의 조회 후 필터링 한계). 무한스크롤은 {@code content.isEmpty()}가 아니라
     * {@code last} 기준으로 구현해야 한다.
     */
    @Operation(summary = "영화 리뷰 목록 조회")
    @GetMapping("/api/movies/{movieId}/reviews")
    public ResponseEntity<PageResponse<ReviewResponse>> getMovieReviews(
            @AuthUser Long viewerId,
            @PathVariable Long movieId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(reviewService.getMovieReviews(viewerId, movieId, pageable)));
    }

    /** upsert. 같은 요청을 반복해도 같은 상태라는 PUT의 의미와 일치한다(4-4 확정). */
    @Operation(summary = "영화 리뷰 작성/수정")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/api/movies/{movieId}/review")
    public ResponseEntity<ReviewResponse> writeReview(@AuthUser(required = true) Long userId,
                                                       @PathVariable Long movieId,
                                                       @Valid @RequestBody ReviewWriteRequest request) {
        return ResponseEntity.ok(reviewService.writeReview(userId, movieId, request));
    }

    @Operation(summary = "영화 리뷰 삭제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/api/movies/{movieId}/review")
    public ResponseEntity<Void> deleteReview(@AuthUser(required = true) Long userId,
                                             @PathVariable Long movieId) {
        reviewService.deleteReview(userId, movieId);
        return ResponseEntity.noContent().build();
    }

    /** 리뷰가 없으면 204 — "아직 리뷰 없음"이 정상 상태임을 표현한다(4-4의 Optional 반환을 HTTP로 옮김). */
    @Operation(summary = "내 리뷰 단건 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/reviews/me")
    public ResponseEntity<ReviewResponse> getMyReview(@AuthUser(required = true) Long userId,
                                                       @RequestParam Long movieId) {
        return reviewService.getMyReview(userId, movieId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
