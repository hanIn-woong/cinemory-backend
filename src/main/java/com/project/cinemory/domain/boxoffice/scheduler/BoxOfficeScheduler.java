package com.project.cinemory.domain.boxoffice.scheduler;

import com.project.cinemory.domain.boxoffice.service.BoxOfficeSyncService;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kofic.KoficProperties;
import com.project.cinemory.global.infra.tmdb.TmdbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 박스오피스 배치 진입점.
 *
 * <p>스케줄러는 <b>시각 결정과 예외 격리</b>만 담당하고 실제 로직은 {@link BoxOfficeSyncService}에 있다.
 * 관리자 수동 트리거도 같은 서비스 메서드를 호출하므로 배치 로직이 이원화되지 않는다.
 *
 * <p>재매칭 상한({@code cinemory.boxoffice.rematch-limit})은 여기서 따로 들고 있지 않는다 —
 * {@link BoxOfficeSyncService#rematchUnlinked(Integer)}에 {@code null}을 넘겨 기본값 판단을
 * Service 한 곳에 맡긴다(5-6-C ③). 같은 값을 두 곳에서 읽으면 한쪽만 바뀌었을 때 조용히 어긋난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoxOfficeScheduler {

    private final BoxOfficeSyncService boxOfficeSyncService;
    private final KoficProperties koficProperties;
    private final MovieSeedService movieSeedService;
    private final TmdbProperties tmdbProperties;

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

    /**
     * 그날 수집분의 미등록 영화 자동 등록(cinemap-spec C-6-2) — 05:00 수집 → <b>05:15 이 시드</b> → 05:30 재매칭.
     * 수동 시드가 실행 중이면 같은 플래그에 막혀 건너뛴다(INFO). TMDB 한도(429)는 시드가 정상 반환으로 처리한다.
     */
    @Scheduled(cron = "${cinemory.boxoffice.reverse-seed-cron}", zone = "Asia/Seoul")
    public void reverseSeedUnmatchedMovies() {
        if (!tmdbProperties.isConfigured()) {
            log.warn("TMDB 토큰이 설정되지 않아 박스오피스 미등록 영화 자동 등록을 건너뜁니다.");
            return;
        }
        try {
            movieSeedService.seedFromLatestBoxOffice();
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SEED_ALREADY_RUNNING) {
                log.info("영화 시드가 이미 실행 중이어서 박스오피스 미등록 영화 자동 등록을 건너뜁니다.");
                return;
            }
            log.error("박스오피스 미등록 영화 자동 등록 실패.", e);
        } catch (Exception e) {
            log.error("박스오피스 미등록 영화 자동 등록 실패.", e);
        }
    }

    /** 미매칭 레코드 재매칭. 일별 수집 직후 실행한다. */
    @Scheduled(cron = "${cinemory.boxoffice.rematch-cron}", zone = "Asia/Seoul")
    public void rematchUnlinkedBoxOffice() {
        try {
            boxOfficeSyncService.rematchUnlinked(null);
        } catch (Exception e) {
            log.error("박스오피스 재매칭 실패.", e);
        }
    }
}
