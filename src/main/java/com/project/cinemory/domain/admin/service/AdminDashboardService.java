package com.project.cinemory.domain.admin.service;

import com.project.cinemory.domain.admin.dto.AdminDashboardResponse;
import com.project.cinemory.domain.admin.dto.AdminDashboardResponse.DailyPoint;
import com.project.cinemory.domain.admin.dto.AdminDashboardResponse.Recent;
import com.project.cinemory.domain.admin.dto.AdminDashboardResponse.Totals;
import com.project.cinemory.domain.admin.repository.AdminDashboardRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 집계(admin-spec 13-1) — 읽기 전용, 스키마 변경 없음. 날짜는 서버 시간대(운영 KST) 기준이다.
 *
 * <p>일별 시리즈는 지표별 쿼리 한 개씩으로 받아 <b>빈 날을 0으로 채우는 일은 여기서</b> 한다. "최근 7일" 지표 중
 * 신규 가입·신규 기록은 일별 시리즈의 마지막 7일 합이고(시리즈가 항상 7일 이상이다), 활성·기록 사용자는
 * <b>7일 구간의 고유 사용자 수</b>라 일별 합으로 구할 수 없어 별도 쿼리다(같은 사람이 여러 날 나와도 1명).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {

    public static final int MIN_DAYS = 7;
    public static final int MAX_DAYS = 90;
    public static final int DEFAULT_DAYS = 30;
    /** "최근" 지표의 고정 구간 — 요청한 days와 무관하다. */
    private static final int RECENT_DAYS = 7;
    private static final int TOP_MOVIES_LIMIT = 10;

    private final AdminDashboardRepository dashboardRepository;
    private final UserRepository userRepository;
    private final MovieRepository movieRepository;
    private final WatchRecordRepository watchRecordRepository;
    private final ReviewRepository reviewRepository;
    private final PersonRepository personRepository;
    private final Clock clock;

    public AdminDashboardResponse getDashboard(int days) {
        return getDashboard(days, LocalDate.now(clock));
    }

    /** 오늘을 받는 형태 — 날짜 경계(KST 자정)를 테스트가 고정해서 볼 수 있게 한다. */
    public AdminDashboardResponse getDashboard(int days, LocalDate today) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "days는 " + MIN_DAYS + "~" + MAX_DAYS + " 사이여야 합니다.");
        }

        LocalDate firstDay = today.minusDays(days - 1L);
        LocalDateTime from = firstDay.atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();   // 오늘 끝까지 포함

        Map<LocalDate, Long> newUsers = dashboardRepository.dailyNewUsers(from, toExclusive);
        Map<LocalDate, Long> activeUsers = dashboardRepository.dailyActiveUsers(from, toExclusive);
        Map<LocalDate, Long> newWatchRecords = dashboardRepository.dailyNewWatchRecords(from, toExclusive);
        Map<LocalDate, Long> newReviews = dashboardRepository.dailyNewReviews(from, toExclusive);
        Map<LocalDate, Long> newMovies = dashboardRepository.dailyNewMovies(from, toExclusive);

        List<DailyPoint> daily = new ArrayList<>(days);
        for (LocalDate date = firstDay; !date.isAfter(today); date = date.plusDays(1)) {
            daily.add(new DailyPoint(date,
                    newUsers.getOrDefault(date, 0L), activeUsers.getOrDefault(date, 0L),
                    newWatchRecords.getOrDefault(date, 0L), newReviews.getOrDefault(date, 0L),
                    newMovies.getOrDefault(date, 0L)));
        }

        return new AdminDashboardResponse(
                totals(),
                recent(daily, today),
                daily,
                dashboardRepository.topMovies(from, toExclusive, TOP_MOVIES_LIMIT));
    }

    private Totals totals() {
        return new Totals(
                userRepository.count(),
                movieRepository.count(),
                watchRecordRepository.count(),
                reviewRepository.count(),
                movieRepository.countBySyncedAtIsNull(),
                personRepository.countMissingKorean());
    }

    private Recent recent(List<DailyPoint> daily, LocalDate today) {
        List<DailyPoint> lastSeven = daily.subList(daily.size() - RECENT_DAYS, daily.size());
        LocalDateTime from = today.minusDays(RECENT_DAYS - 1L).atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
        return new Recent(
                lastSeven.stream().mapToLong(DailyPoint::newUsers).sum(),
                dashboardRepository.activeUsers(from, toExclusive),
                dashboardRepository.recordingUsers(from, toExclusive),
                lastSeven.stream().mapToLong(DailyPoint::newWatchRecords).sum());
    }
}
