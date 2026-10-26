package com.project.cinemory.domain.recommendation.controller;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.admin.service.AuditDetail;
import com.project.cinemory.domain.recommendation.dto.EmbeddingSyncResponse;
import com.project.cinemory.domain.recommendation.dto.RecommendationDebugResponse;
import com.project.cinemory.domain.recommendation.service.EmbeddingSyncService;
import com.project.cinemory.domain.recommendation.service.RecommendationDebugService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import com.project.cinemory.global.security.resolver.CurrentAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3-b 추천 관리자 엔드포인트 (controller-layer-spec 5-14-G). 인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다(5-6-B).
 */
@Tag(name = "Recommendation")
@RestController
@RequiredArgsConstructor
public class AdminRecommendationController {

    /** 한 번에 훑는 영화 수 — 1,000편 ≈ 임베딩 요청 10회로 Nginx 60초 안에 끝난다(resync가 limit으로 나누는 것과 같은 이유). */
    static final int DEFAULT_SYNC_LIMIT = 1_000;
    static final int MAX_SYNC_LIMIT = 5_000;

    private final EmbeddingSyncService embeddingSyncService;
    private final RecommendationDebugService debugService;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "영화 임베딩 수동 실행 — 해시가 바뀐 영화만 (스케줄 06:00과 실행 플래그 공유, 실행 중이면 409)",
            description = "첫 적재 · 시연 전 즉시 반영 · 인물명 일괄 보강 직후 · 모델/템플릿 변경 직후 · 429/장애 중단 후 재개에 쓴다. "
                    + "limit 기본 1000(1~5000). 응답의 lastProcessedId를 다음 호출의 afterId로 넘겨 이어받는다. "
                    + "완료 후 벡터·특징 인덱스가 재생성된다. 감사 로그 EMBEDDING_SYNC.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/embeddings/sync")
    public ResponseEntity<EmbeddingSyncResponse> syncEmbeddings(@RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) Long afterId,
                                                                @CurrentAdmin AuditActor actor) {
        int resolvedLimit = limit != null ? limit : DEFAULT_SYNC_LIMIT;
        if (resolvedLimit < 1 || resolvedLimit > MAX_SYNC_LIMIT || (afterId != null && afterId < 0)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        EmbeddingSyncResponse response = EmbeddingSyncResponse.from(embeddingSyncService.sync(afterId, resolvedLimit));
        // 성공한 뒤에만 남긴다(409는 예외로 위에서 끝난다). 임베딩은 트랜잭션 밖이라 같은 트랜잭션으로 묶을 수 없다.
        adminAuditService.record(actor, AdminAction.EMBEDDING_SYNC, null, null, AuditDetail.of(
                "limit", resolvedLimit, "afterId", afterId,
                "embedded", response.embedded(), "unchanged", response.unchanged(), "failed", response.failed(),
                "stoppedByRateLimit", response.stoppedByRateLimit(), "lastProcessedId", response.lastProcessedId()));
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "추천 점수 분해 — 사용자 프로필(평가/찜 기여 분리)과 결과 100편의 항별 점수 (q가 있으면 자연어)",
            description = "캐시를 쓰지 않고 항상 계산하며 호출 제한에 세지 않는다. 가중치 튜닝용. 특정 회원의 취향 정보 열람이므로 감사 로그 RECOMMENDATION_DEBUG_VIEW.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/recommendations/debug")
    public ResponseEntity<RecommendationDebugResponse> debug(@RequestParam Long userId,
                                                             @RequestParam(required = false) String q,
                                                             @CurrentAdmin AuditActor actor) {
        RecommendationDebugResponse response = debugService.debug(userId, q);
        adminAuditService.record(actor, AdminAction.RECOMMENDATION_DEBUG_VIEW, AdminTargetType.USER, userId,
                AuditDetail.of("query", response.query()));
        return ResponseEntity.ok(response);
    }
}
