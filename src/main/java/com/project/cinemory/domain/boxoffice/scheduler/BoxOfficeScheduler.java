package com.project.cinemory.domain.boxoffice.scheduler;

import com.project.cinemory.domain.boxoffice.service.BoxOfficeSyncService;
import com.project.cinemory.global.infra.kofic.KoficProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 박스오피스 배치 진입점.
 *
 * <p>스케줄러는 <b>시각 결정과 예외 격리</b>만 담당하고 실제 로직은 {@link BoxOfficeSyncService}에 있다.
 * 관리자 수동 트리거도 같은 서비스 메서드를 호출하므로 배치 로직이 이원화되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoxOfficeScheduler {

    private final BoxOfficeSyncService boxOfficeSyncService;
    private final KoficProperties koficProperties;

    @Value("${cinemory.boxoffice.rematch-limit}")
    private int rematchLimit;

    /**
     * 일별 수집. KOFIC은 전일 데이터를 익일 제공하므로 '어제'를 기준으로 호출한다.
     *
     * <p>수집 실패가 애플리케이션 전체로 전파되면 안 되므로 예외를 잡아 로깅만 한다.
     * 실패한 날짜는 관리자 수동 트리거로 재수집한다(수집이 멱등하므로 안전).
     */
    @Scheduled(cron = "${cinemory.boxoffice.daily-sync-cron}", zone = "Asia/Seoul")
    public void syncDailyBoxOffice() {
        if (!koficProperties.isConfigured()) {
            log.warn("KOFIC API 키가 설정되지 않아 박스오피스 수집을 건너뜁니다.");
            return;
        }

        LocalDate targetDate = LocalDate.now().minusDays(1);
        try {
            boxOfficeSyncService.syncDaily(targetDate);
        } catch (Exception e) {
            log.error("박스오피스 일별 수집 실패. targetDate={}", targetDate, e);
        }
    }

    /** 미매칭 레코드 재매칭. 일별 수집 직후 실행한다. */
    @Scheduled(cron = "${cinemory.boxoffice.rematch-cron}", zone = "Asia/Seoul")
    public void rematchUnlinkedBoxOffice() {
        try {
            boxOfficeSyncService.rematchUnlinked(rematchLimit);
        } catch (Exception e) {
            log.error("박스오피스 재매칭 실패.", e);
        }
    }
}
