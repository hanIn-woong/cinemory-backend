package com.project.cinemory.domain.report.service;

import com.project.cinemory.domain.report.dto.DecadeCountResponse;
import com.project.cinemory.domain.report.dto.FiveStarMovieResponse;
import com.project.cinemory.domain.report.dto.MonthlyTrendItemResponse;
import com.project.cinemory.domain.report.dto.MovieRatingGapResponse;
import com.project.cinemory.domain.report.dto.OldestWatchedResponse;
import com.project.cinemory.domain.report.dto.OttPlatformCountResponse;
import com.project.cinemory.domain.report.dto.PersonRankItemResponse;
import com.project.cinemory.domain.report.dto.PreferenceItemResponse;
import com.project.cinemory.domain.report.dto.RatingBucketResponse;
import com.project.cinemory.domain.report.dto.ReportCalendarResponse;
import com.project.cinemory.domain.report.dto.ReportCalendarResponse.CalendarDayResponse;
import com.project.cinemory.domain.report.dto.ReportCalendarResponse.CalendarRecordItemResponse;
import com.project.cinemory.domain.report.dto.ReportMonthlyResponse;
import com.project.cinemory.domain.report.dto.ReportStatisticsResponse;
import com.project.cinemory.domain.report.dto.ReportYearlyResponse;
import com.project.cinemory.domain.report.dto.RewatchItemResponse;
import com.project.cinemory.domain.report.dto.WatchTypeCountResponse;
import com.project.cinemory.domain.report.dto.WeekdayCountResponse;
import com.project.cinemory.domain.report.repository.CalendarRecordProjection;
import com.project.cinemory.domain.report.repository.DecadeProjection;
import com.project.cinemory.domain.report.repository.MonthlyTrendProjection;
import com.project.cinemory.domain.report.repository.MostWatchedProjection;
import com.project.cinemory.domain.report.repository.PeriodSummaryProjection;
import com.project.cinemory.domain.report.repository.PersonPreferenceProjection;
import com.project.cinemory.domain.report.repository.PreferenceProjection;
import com.project.cinemory.domain.report.repository.RatingBucketProjection;
import com.project.cinemory.domain.report.repository.ReportRepository;
import com.project.cinemory.domain.report.repository.ReportSummaryProjection;
import com.project.cinemory.domain.report.repository.WatchTypeProjection;
import com.project.cinemory.domain.report.repository.WeekdayProjection;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * M3-a 시청 분석 리포트 집계(4-8). 전부 읽기 전용이며 쓰기 메서드가 없다.
 * 설계 근거는 {@code docs/M3a-report-spec.md}, 쿼리 확정은 {@code service-layer-spec.md} 4-8.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private static final String UNSPECIFIED_WATCH_TYPE = "UNSPECIFIED";
    private static final int TOP_GENRE_COUNTRY_LIMIT = 5;
    private static final int TOP_ACTOR_DIRECTOR_LIMIT = 3;
    private static final int REWATCH_TOP_LIMIT = 5;
    // "고전"의 경계(4-4) — 조정될 가능성이 커 상수로 뺀다.
    private static final int CLASSIC_YEAR_BOUNDARY = 2000;
    // 대중 평점 최댓값 지표의 vote_count 하한(4-5) — 투표 수 적은 무명작이 1위로 올라오는 것을 막는다.
    private static final int MIN_VOTE_COUNT_FOR_RATING_GAP = 100;
    // monthlyTrend 상한(4-2) — 20년치. 이상 데이터 방어용.
    private static final int MONTHLY_TREND_MAX_SIZE = 240;
    private static final int MIN_REPORT_YEAR = 1900;
    private static final int MAX_REPORT_YEAR = 2100;

    private final ReportRepository reportRepository;
    private final ReviewRepository reviewRepository;
    private final UserAccessPolicy userAccessPolicy;

    public ReportStatisticsResponse getStatistics(Long viewerId, Long targetUserId) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        ReportSummaryProjection summary = reportRepository.findSummary(targetUserId);
        long movieCount = summary.getMovieCount();
        long watchCount = summary.getWatchCount();

        List<PreferenceItemResponse> topGenres = mapPreferences(
                reportRepository.findTopGenres(targetUserId, TOP_GENRE_COUNTRY_LIMIT));
        List<PreferenceItemResponse> topCountries = mapPreferences(
                reportRepository.findTopCountries(targetUserId, TOP_GENRE_COUNTRY_LIMIT));
        List<PersonRankItemResponse> topActors = mapPersonPreferences(
                reportRepository.findTopActors(targetUserId, TOP_ACTOR_DIRECTOR_LIMIT));
        List<PersonRankItemResponse> topDirectors = mapPersonPreferences(
                reportRepository.findTopDirectors(targetUserId, TOP_ACTOR_DIRECTOR_LIMIT));

        List<RatingBucketResponse> ratingDistribution =
                fillRatingBuckets(reportRepository.findRatingDistribution(targetUserId));

        List<WatchTypeCountResponse> watchTypeDistribution =
                mapWatchTypeDistribution(reportRepository.findWatchTypeDistribution(targetUserId));

        List<OttPlatformCountResponse> ottPlatformDistribution = reportRepository.findOttPlatformDistribution(targetUserId)
                .stream()
                .map(p -> new OttPlatformCountResponse(p.getId(), p.getName(), p.getCount()))
                .toList();

        List<DecadeProjection> decadeRows = reportRepository.findReleaseDecadeDistribution(targetUserId);
        List<DecadeCountResponse> releaseDecadeDistribution = decadeRows.stream()
                .map(d -> new DecadeCountResponse(d.getDecade(), d.getCount()))
                .toList();
        long classicCount = classicCountFrom(decadeRows);

        OldestWatchedResponse oldestWatched = mapNullable(
                reportRepository.findOldestWatched(targetUserId), OldestWatchedResponse::from);

        Double ratingBiasAverageRaw = reportRepository.findRatingBiasAverage(targetUserId);
        BigDecimal ratingBiasAverage = ratingBiasAverageRaw == null ? null : roundTo1(ratingBiasAverageRaw);
        MovieRatingGapResponse mostOverratedByMe = mapNullable(
                reportRepository.findMostOverratedByMe(targetUserId, MIN_VOTE_COUNT_FOR_RATING_GAP),
                MovieRatingGapResponse::from);
        MovieRatingGapResponse mostUnderratedByMe = mapNullable(
                reportRepository.findMostUnderratedByMe(targetUserId, MIN_VOTE_COUNT_FOR_RATING_GAP),
                MovieRatingGapResponse::from);

        List<RewatchItemResponse> rewatchTop = reportRepository.findRewatchTop(targetUserId, REWATCH_TOP_LIMIT).stream()
                .map(RewatchItemResponse::from)
                .toList();
        long rewatchCount = watchCount - movieCount;

        List<WeekdayCountResponse> weekdayDistribution = reportRepository.findWeekdayDistribution(targetUserId).stream()
                .map(w -> new WeekdayCountResponse(w.getWeekday(), w.getCount()))
                .toList();

        List<MonthlyTrendItemResponse> monthlyTrend =
                fillMonthlyTrendGaps(reportRepository.findMonthlyTrend(targetUserId));

        LocalDateTime firstRecordCreatedAt = reportRepository.findFirstRecordCreatedAt(targetUserId);
        LocalDate firstRecordDate = firstRecordCreatedAt == null ? null : firstRecordCreatedAt.toLocalDate();
        double reviewRate = movieCount == 0 ? 0.0 : (double) reviewRepository.countByUserId(targetUserId) / movieCount;

        return new ReportStatisticsResponse(
                movieCount,
                watchCount,
                summary.getUndatedCount(),
                summary.getTotalWatchedMinutes(),
                roundToNullable1(summary.getAverageRating()),
                ratingDistribution,
                topGenres,
                topCountries,
                topActors,
                topDirectors,
                monthlyTrend,
                watchTypeDistribution,
                ottPlatformDistribution,
                releaseDecadeDistribution,
                classicCount,
                oldestWatched,
                ratingBiasAverage,
                mostOverratedByMe,
                mostUnderratedByMe,
                rewatchCount,
                rewatchTop,
                weekdayDistribution,
                firstRecordDate,
                reviewRate
        );
    }

    /** 기간 리포트 규칙(4-8-H)을 따른다 — 대표 플래그를 보지 않는다. */
    public ReportMonthlyResponse getMonthlyReport(Long viewerId, Long targetUserId, int year, int month) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);
        validatePeriod(year, month);

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate from = yearMonth.atDay(1);
        LocalDate to = yearMonth.atEndOfMonth();

        PeriodSummaryProjection summary = reportRepository.findPeriodSummary(targetUserId, from, to);
        Double averageRating = reportRepository.findPeriodAverageRating(targetUserId, from, to);
        List<RatingBucketResponse> ratingDistribution =
                fillRatingBuckets(reportRepository.findPeriodRatingDistribution(targetUserId, from, to));
        List<WatchTypeCountResponse> watchTypeDistribution =
                mapWatchTypeDistribution(reportRepository.findPeriodWatchTypeDistribution(targetUserId, from, to));

        PersonRankItemResponse mostWatchedDirector = mapNullable(
                reportRepository.findMostWatchedDirector(targetUserId, from, to), PersonRankItemResponse::from);

        Integer mostWatchedWeekday = reportRepository.findMostWatchedWeekday(targetUserId, from, to);

        return new ReportMonthlyResponse(
                year,
                month,
                summary.getMovieCount(),
                summary.getWatchCount(),
                summary.getTotalWatchedMinutes(),
                roundToNullable1(averageRating),
                ratingDistribution,
                watchTypeDistribution,
                mostWatchedDirector,
                mostWatchedWeekday
        );
    }

    /**
     * 연간 리포트(4-8-H). 월간 지표 전부 + 연간 전용. 진행 중인 연도 판정은 클라이언트 몫이다(RA-2) —
     * 응답에는 {@code year}만 담는다.
     */
    public ReportYearlyResponse getYearlyReport(Long viewerId, Long targetUserId, int year) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);
        validateYear(year);

        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);

        PeriodSummaryProjection summary = reportRepository.findPeriodSummary(targetUserId, from, to);
        Double averageRating = reportRepository.findPeriodAverageRating(targetUserId, from, to);
        List<RatingBucketResponse> ratingDistribution =
                fillRatingBuckets(reportRepository.findPeriodRatingDistribution(targetUserId, from, to));
        List<WatchTypeCountResponse> watchTypeDistribution =
                mapWatchTypeDistribution(reportRepository.findPeriodWatchTypeDistribution(targetUserId, from, to));

        PersonRankItemResponse mostWatchedDirector = mapNullable(
                reportRepository.findMostWatchedDirector(targetUserId, from, to), PersonRankItemResponse::from);
        PersonRankItemResponse mostWatchedActor = mapNullable(
                reportRepository.findMostWatchedActor(targetUserId, from, to), PersonRankItemResponse::from);
        List<PreferenceItemResponse> mostWatchedGenres = mapMostWatched(
                reportRepository.findMostWatchedGenres(targetUserId, from, to, TOP_GENRE_COUNTRY_LIMIT));
        List<PreferenceItemResponse> mostWatchedCountries = mapMostWatched(
                reportRepository.findMostWatchedCountries(targetUserId, from, to, TOP_GENRE_COUNTRY_LIMIT));

        List<MonthlyTrendItemResponse> monthlyTrend =
                fillYearMonths(year, reportRepository.findPeriodMonthlyTrend(targetUserId, from, to));
        List<WeekdayCountResponse> weekdayDistribution =
                fillWeekdays(reportRepository.findPeriodWeekdayDistribution(targetUserId, from, to));

        List<FiveStarMovieResponse> fiveStarMovies = reportRepository.findFiveStarMovies(targetUserId, from, to).stream()
                .map(FiveStarMovieResponse::from)
                .toList();

        return new ReportYearlyResponse(
                year,
                summary.getMovieCount(),
                summary.getWatchCount(),
                summary.getTotalWatchedMinutes(),
                roundToNullable1(averageRating),
                ratingDistribution,
                watchTypeDistribution,
                mostWatchedDirector,
                mostWatchedActor,
                mostWatchedGenres,
                mostWatchedCountries,
                monthlyTrend,
                weekdayDistribution,
                fiveStarMovies
        );
    }

    public ReportCalendarResponse getCalendar(Long viewerId, Long targetUserId, int year, int month) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);
        validatePeriod(year, month);

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate from = yearMonth.atDay(1);
        LocalDate to = yearMonth.atEndOfMonth();

        List<CalendarRecordProjection> records = reportRepository.findCalendarRecords(targetUserId, from, to);

        Map<LocalDate, List<CalendarRecordItemResponse>> byDate = records.stream()
                .collect(Collectors.groupingBy(
                        CalendarRecordProjection::getWatchDate,
                        LinkedHashMap::new,
                        Collectors.mapping(CalendarRecordItemResponse::from, Collectors.toList())));

        List<CalendarDayResponse> days = byDate.entrySet().stream()
                .map(entry -> new CalendarDayResponse(entry.getKey(), entry.getValue()))
                .toList();

        return new ReportCalendarResponse(year, month, days);
    }

    /** {@code year} 1900~2100 sanity 범위, {@code month} 1~12. 미래 월은 통과시킨다(RA-2). */
    private void validatePeriod(int year, int month) {
        validateYear(year);
        if (month < 1 || month > 12) {
            throw new BusinessException(ErrorCode.INVALID_REPORT_PERIOD);
        }
    }

    /** 연간이 {@code year}만 검증할 수 있도록 분리했다(4-8-H). 미래 연도는 통과시킨다. */
    private void validateYear(int year) {
        if (year < MIN_REPORT_YEAR || year > MAX_REPORT_YEAR) {
            throw new BusinessException(ErrorCode.INVALID_REPORT_PERIOD);
        }
    }

    private List<PreferenceItemResponse> mapPreferences(List<PreferenceProjection> projections) {
        return projections.stream().map(PreferenceItemResponse::from).toList();
    }

    private List<PersonRankItemResponse> mapPersonPreferences(List<PersonPreferenceProjection> projections) {
        return projections.stream().map(PersonRankItemResponse::from).toList();
    }

    private List<PreferenceItemResponse> mapMostWatched(List<MostWatchedProjection> projections) {
        return projections.stream().map(PreferenceItemResponse::from).toList();
    }

    /** 연간 월별 추이 — 누적과 달리 구간이 첫~마지막 기록이 아니라 1~12월 고정이다(4-8-H). */
    private List<MonthlyTrendItemResponse> fillYearMonths(int year, List<MonthlyTrendProjection> rows) {
        Map<Integer, MonthlyTrendProjection> byMonth = rows.stream()
                .collect(Collectors.toMap(MonthlyTrendProjection::getMonth, Function.identity()));
        List<MonthlyTrendItemResponse> result = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            MonthlyTrendProjection row = byMonth.get(month);
            if (row == null) {
                result.add(new MonthlyTrendItemResponse(year, month, 0, 0, 0));
            } else {
                result.add(new MonthlyTrendItemResponse(
                        year, month, row.getWatchCount(), row.getMovieCount(), row.getWatchedMinutes()));
            }
        }
        return result;
    }

    /** 요일 7개 고정(1=일 ~ 7=토), 빈 요일은 count 0. */
    private List<WeekdayCountResponse> fillWeekdays(List<WeekdayProjection> rows) {
        Map<Integer, Long> countByWeekday = rows.stream()
                .collect(Collectors.toMap(WeekdayProjection::getWeekday, WeekdayProjection::getCount));
        List<WeekdayCountResponse> result = new ArrayList<>(7);
        for (int weekday = 1; weekday <= 7; weekday++) {
            result.add(new WeekdayCountResponse(weekday, countByWeekday.getOrDefault(weekday, 0L)));
        }
        return result;
    }

    /** 존재하는 버킷만 돌아오므로 1~10 전부를 count 0 포함해 채운다(RA-5). */
    private List<RatingBucketResponse> fillRatingBuckets(List<RatingBucketProjection> rows) {
        Map<Integer, Long> countByRating = rows.stream()
                .collect(Collectors.toMap(RatingBucketProjection::getRating, RatingBucketProjection::getCount));
        List<RatingBucketResponse> result = new ArrayList<>(10);
        for (int rating = 1; rating <= 10; rating++) {
            result.add(new RatingBucketResponse(rating, countByRating.getOrDefault(rating, 0L)));
        }
        return result;
    }

    /** watch_type이 nullable이라 NULL 그룹을 UNSPECIFIED로 매핑한다(4-8-C ④). */
    private List<WatchTypeCountResponse> mapWatchTypeDistribution(List<WatchTypeProjection> rows) {
        return rows.stream()
                .map(row -> new WatchTypeCountResponse(
                        row.getWatchType() == null ? UNSPECIFIED_WATCH_TYPE : row.getWatchType(),
                        row.getCount()))
                .toList();
    }

    /** classicCount = 2000년 이전(1990s 이하) 버킷의 합 — 별도 쿼리 없이 연대 분포에서 도출한다. */
    private long classicCountFrom(List<DecadeProjection> decadeRows) {
        return decadeRows.stream()
                .filter(d -> isClassicBucket(d.getDecade()))
                .mapToLong(DecadeProjection::getCount)
                .sum();
    }

    private boolean isClassicBucket(String decade) {
        return switch (decade) {
            case "1990s", "1980s", "1970s", "~1960s" -> true;
            default -> false;
        };
    }

    /** 첫 기록 달 ~ 마지막 기록 달 사이 공백을 0으로 채운다(4-2). 상한 240개(20년). */
    private List<MonthlyTrendItemResponse> fillMonthlyTrendGaps(List<MonthlyTrendProjection> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<YearMonth, MonthlyTrendProjection> byYearMonth = rows.stream()
                .collect(Collectors.toMap(
                        r -> YearMonth.of(r.getYear(), r.getMonth()),
                        r -> r,
                        (a, b) -> a,
                        LinkedHashMap::new));

        YearMonth first = YearMonth.of(rows.get(0).getYear(), rows.get(0).getMonth());
        YearMonth last = YearMonth.of(rows.get(rows.size() - 1).getYear(), rows.get(rows.size() - 1).getMonth());

        List<MonthlyTrendItemResponse> result = new ArrayList<>();
        YearMonth cursor = first;
        while (!cursor.isAfter(last) && result.size() < MONTHLY_TREND_MAX_SIZE) {
            MonthlyTrendProjection row = byYearMonth.get(cursor);
            if (row == null) {
                result.add(new MonthlyTrendItemResponse(cursor.getYear(), cursor.getMonthValue(), 0, 0, 0));
            } else {
                result.add(new MonthlyTrendItemResponse(
                        row.getYear(), row.getMonth(), row.getWatchCount(), row.getMovieCount(), row.getWatchedMinutes()));
            }
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    private <T, R> R mapNullable(T value, Function<T, R> mapper) {
        return value == null ? null : mapper.apply(value);
    }

    private BigDecimal roundToNullable1(Double value) {
        return value == null ? null : roundTo1(value);
    }

    private BigDecimal roundTo1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
