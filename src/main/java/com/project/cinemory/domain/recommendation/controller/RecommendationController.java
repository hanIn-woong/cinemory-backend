package com.project.cinemory.domain.recommendation.controller;

import com.project.cinemory.domain.recommendation.dto.ForYouBatchResponse;
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

import java.util.List;

/**
 * M3-b 추천 (controller-layer-spec 5-14). 내 취향 추천은 10편 묶음({@link ForYouBatchResponse}, 5-14-K),
 * 자연어로 찾기는 10편 x 최대 2페이지({@link RecommendationResponse}, 5-14-D)다.
 *
 * <p>{@code sort}는 받지 않는다(5-0-D). 자연어의 {@code size}는 받지 않고 10으로 고정이다 - {@code Pageable}에 실려 와도 {@code page}만 본다.
 */
@Tag(name = "Recommendation")
@RestController
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationPageService recommendationPageService;

    @Operation(summary = "내 취향 추천 10편 묶음 — 비로그인 허용(게스트는 평이 좋고 알려진 영화 상위 풀의 묶음)",
            description = "상위 후보 50편에서 점수 비례 무작위로 10편을 뽑아 묶음으로 준다. next=false(기본)는 같은 날·같은 취향 프로필이면 "
                    + "다시 들어와도 같은 묶음이고, next=true는 직전 3묶음을 뺀 새 묶음('다른 추천 보기'). 기록·찜이 바뀌면 새 묶음. "
                    + "게스트는 서버에 상태가 없어 앞서 받은 영화 id를 exclude(콤마 구분, 최대 30개, 오래된 것이 앞)로 보낸다 - 로그인 사용자는 무시. "
                    + "숫자가 아닌 exclude는 400 INVALID_TYPE_VALUE. 호출 제한 없음.")
    @GetMapping(RecommendationPaths.FOR_YOU)
    public ResponseEntity<ForYouBatchResponse> forYou(@AuthUser Long userId,
                                                      @RequestParam(defaultValue = "false") boolean next,
                                                      @RequestParam(required = false) List<Long> exclude) {
        return ResponseEntity.ok(recommendationPageService.forYou(userId, next, exclude));
    }

    @Operation(summary = "자연어로 찾기 — 로그인 필수, 10편 + 결과 더 보기 1회(최대 20편), 사용자당 분당 10 · 일 200회(실제 임베딩 호출만 센다)",
            description = "page는 0 또는 1, 크기는 10 고정(size를 보내도 무시)이며 page ≥ 2는 빈 결과(last=true). 결과는 무작위로 섞지 않는 결정적 순위다. q는 정규화 후 2~100자. 초과 시 429 RECOMMENDATION_RATE_LIMITED + Retry-After. "
                    + "임베딩 호출이 실패하거나 3초를 넘기면 취향 추천 결과로 대체하고 degraded=true(캐시하지 않음). "
                    + "'90분 이내' 같은 명시 상영시간 조건은 하드 필터, '짧은'은 가산점이며 conditions로 알려 준다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(RecommendationPaths.SEARCH)
    public ResponseEntity<RecommendationResponse> search(@AuthUser(required = true) Long userId,
                                                         @RequestParam String q,
                                                         @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(recommendationPageService.search(userId, q, pageable.getPageNumber()));
    }
}
