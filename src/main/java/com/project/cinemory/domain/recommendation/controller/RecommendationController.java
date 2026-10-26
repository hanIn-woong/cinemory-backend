package com.project.cinemory.domain.recommendation.controller;

import com.project.cinemory.domain.recommendation.dto.RecommendationResponse;
import com.project.cinemory.domain.recommendation.service.RecommendationPageService;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3-b 추천 (controller-layer-spec 5-14). 두 엔드포인트가 같은 {@link RecommendationResponse}를 돌려준다.
 *
 * <p>{@code sort}는 받지 않는다(5-0-D) — {@code Pageable}에 실려 와도 쓰지 않고 {@code page}·{@code size}만 본다.
 * {@code size}는 Service에서 50으로 자른다. 순위 100편은 확정돼 있고 2페이지부터는 캐시에서 잘라 준다.
 */
@Tag(name = "Recommendation")
@RestController
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationPageService recommendationPageService;

    @Operation(summary = "내 취향 추천 — 비로그인 허용(게스트는 평이 좋고 알려진 영화 순의 공통 결과)",
            description = "로그인하면 평가 기록·찜으로 만든 취향 점수가 반영된다. 기록도 찜도 없으면 personalized=false로 게스트와 같은 결과. "
                    + "0페이지는 항상 새로 계산하고, 1페이지부터는 그 결과를 잘라 준다. 총 100편(page×size ≥ 100이면 빈 content).")
    @GetMapping(RecommendationPaths.FOR_YOU)
    public ResponseEntity<RecommendationResponse> forYou(@AuthUser Long userId,
                                                         @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(recommendationPageService.forYou(userId, pageable.getPageNumber(), pageable.getPageSize()));
    }

    @Operation(summary = "자연어로 찾기 — 로그인 필수, 사용자당 분당 10 · 일 200회(실제 임베딩 호출만 센다)",
            description = "q는 정규화 후 2~100자. 초과 시 429 RECOMMENDATION_RATE_LIMITED + Retry-After. "
                    + "임베딩 호출이 실패하거나 3초를 넘기면 취향 추천 결과로 대체하고 degraded=true(캐시하지 않음). "
                    + "'90분 이내' 같은 명시 상영시간 조건은 하드 필터, '짧은'은 가산점이며 conditions로 알려 준다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(RecommendationPaths.SEARCH)
    public ResponseEntity<RecommendationResponse> search(@AuthUser(required = true) Long userId,
                                                         @RequestParam String q,
                                                         @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(recommendationPageService.search(userId, q, pageable.getPageNumber(), pageable.getPageSize()));
    }
}
