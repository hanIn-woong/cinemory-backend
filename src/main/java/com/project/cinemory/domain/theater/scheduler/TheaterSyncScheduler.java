package com.project.cinemory.domain.theater.scheduler;

import com.project.cinemory.domain.theater.dto.TheaterSeedReport;
import com.project.cinemory.domain.theater.service.TheaterSeedService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 극장 주간 동기화(cinemap-spec C-5-3) — 수동 시드와 같은 서비스·같은 실행 플래그를 쓴다. 관리자 행위가 아니므로 감사 로그는 남기지 않는다
 * (애플리케이션 로그만). 대량 숨김 방지는 항상 켜 둔다 — 스케줄이 대량 폐업을 알아서 확정하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TheaterSyncScheduler {

    private final TheaterSeedService theaterSeedService;

    @Scheduled(cron = "${cinemory.theater.sync.cron:0 0 4 * * MON}", zone = "Asia/Seoul")
    public void syncTheaters() {
        if (!theaterSeedService.isConfigured()) {
            log.warn("공공데이터·카카오 키가 설정되지 않아 극장 주간 동기화를 건너뜁니다.");
            return;
        }
        try {
            TheaterSeedReport report = theaterSeedService.seed(false, false);
            if (report.aborted()) {
                log.error("극장 주간 동기화 중단 — 아무것도 반영하지 않았습니다. {}", report.abortReason());
                return;
            }
            log.info("극장 주간 동기화 완료. 신규={}, 숨김={}, 복구={}, 숨김 보류={}, 좌표 재사용={}, 카카오 호출={}, 예매 링크 있는데 숨김={}",
                    report.newTheaters(), report.closedTheaters(), report.reopenedTheaters(), report.massCloseHeld(),
                    report.geocodeReused(), report.geocodeCalled(), report.closedWithBookingUrl().size());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.THEATER_SEED_ALREADY_RUNNING) {
                log.info("극장 시드가 이미 실행 중이어서 이번 주간 동기화를 건너뜁니다.");
                return;
            }
            log.error("극장 주간 동기화 실패.", e);
        } catch (Exception e) {
            log.error("극장 주간 동기화 실패.", e);
        }
    }
}
