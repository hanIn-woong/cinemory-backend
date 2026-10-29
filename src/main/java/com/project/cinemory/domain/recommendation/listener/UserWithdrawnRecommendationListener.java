package com.project.cinemory.domain.recommendation.listener;

import com.project.cinemory.domain.recommendation.service.RecommendationCache;
import com.project.cinemory.domain.recommendation.service.RecommendationRateLimiter;
import com.project.cinemory.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 탈퇴 커밋 후 추천 쪽 인메모리 상태를 비운다 (account-integrity-spec C-2 6단계).
 * 이미 탈퇴는 끝났으므로 실패해도 응답에 영향을 주지 않는다 — 캐시는 TTL로 어차피 사라진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserWithdrawnRecommendationListener {

    private final RecommendationCache cache;
    private final RecommendationRateLimiter rateLimiter;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserWithdrawn(UserWithdrawnEvent event) {
        try {
            cache.evictUser(event.userId());
            rateLimiter.evict(event.userId());
        } catch (RuntimeException e) {
            log.warn("탈퇴 후 추천 캐시 정리에 실패했습니다. userId={}", event.userId(), e);
        }
    }
}
