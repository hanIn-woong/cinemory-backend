package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.gemini.GeminiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 임베딩 배치 진입점 — 매일 06:00 (박스오피스 05:00 · 재매칭 05:30 이후, M3b-recommendation-spec E-4-4).
 *
 * <p>{@code BoxOfficeScheduler}와 같은 구조다: 시각 결정과 예외 격리만 하고 로직은 {@link EmbeddingSyncService}에 있다.
 * 수동 실행도 같은 서비스를 호출하므로 {@code running} 플래그가 공유된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingSyncScheduler {

    private final EmbeddingSyncService embeddingSyncService;
    private final GeminiProperties geminiProperties;
    private final EmbeddingSyncProperties properties;

    @Scheduled(cron = "${cinemory.recommend.embedding-sync.cron}", zone = "Asia/Seoul")
    public void syncEmbeddings() {
        if (!geminiProperties.isConfigured()) {
            log.warn("Gemini API 키가 설정되지 않아 임베딩 배치를 건너뜁니다.");
            return;
        }
        try {
            // 처음부터 훑되(변경 없는 영화는 요청 0) 실제 임베딩은 scheduled-limit까지만 — 나머지 일 한도는 사용자 질의 몫(OP-1-2).
            int limit = properties.scheduledLimit();
            embeddingSyncService.sync(null, null, limit > 0 ? limit : null);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.EMBEDDING_SYNC_ALREADY_RUNNING) {
                log.warn("임베딩 배치가 이미 실행 중이어서 오늘 스케줄 실행을 건너뜁니다.");
                return;
            }
            log.error("임베딩 배치 실패.", e);
        } catch (Exception e) {
            log.error("임베딩 배치 실패.", e);
        }
    }
}
