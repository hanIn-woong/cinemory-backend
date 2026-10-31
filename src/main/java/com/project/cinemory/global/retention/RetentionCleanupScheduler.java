package com.project.cinemory.global.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간 정리 진입점 — 매일 04:10 (settings-spec ST-10 ④). 03:30 DB 백업(cinemory-backup.timer) 뒤, 05:00 박스오피스 수집 앞이라 서로 겹치지 않는다.
 * 스케줄러는 시각 결정과 예외 격리만 맡는다. 실패해도 앱 전체로 전파하지 않고 다음 날 다시 돈다(삭제는 멱등).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleanupScheduler {

    private final RetentionCleanupService retentionCleanupService;

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    public void run() {
        try {
            retentionCleanupService.cleanup();
        } catch (Exception e) {
            log.error("보관 기간 정리 실패", e);
        }
    }
}
