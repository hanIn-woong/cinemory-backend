package com.project.cinemory.domain.admin.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 대시보드(admin-spec 13-1) — 읽기 전용 집계. 날짜는 KST(서버 시간대) 기준이다.
 *
 * @param totals    지금 시점 누적
 * @param recent    최근 7일(요청한 {@code days}와 무관하게 고정)
 * @param daily     요청한 {@code days}일, 오래된 날 → 오늘 순. 빈 날은 0으로 채운다
 * @param topMovies 최근 {@code days}일 기록 수 TOP 10
 */
public record AdminDashboardResponse(Totals totals, Recent recent, List<DailyPoint> daily, List<TopMovie> topMovies) {

    /**
     * @param moviesNeverSynced     {@code synced_at IS NULL} — 동기화 이력 없는 영화
     * @param personsMissingKorean  한글 이름이 하나도 없는 인물(admin-spec 12-3 {@code missingKorean}과 같은 정의)
     */
    public record Totals(long users, long movies, long watchRecords, long reviews,
                         long moviesNeverSynced, long personsMissingKorean) {
    }

    /**
     * @param activeUsers    최근 7일에 리프레시 토큰이 발급된 사용자 수(로그인 + 재발급, 중복 제거 — BD-9의 근사치)
     * @param recordingUsers 최근 7일에 시청 기록을 남긴 사용자 수
     */
    public record Recent(long newUsers, long activeUsers, long recordingUsers, long newWatchRecords) {
    }

    /** {@code activeUsers}는 그날 리프레시 토큰이 발급된 사용자 수(중복 제거). {@code newMovies}는 그날 등록된 영화 수. */
    public record DailyPoint(LocalDate date, long newUsers, long activeUsers, long newWatchRecords,
                             long newReviews, long newMovies) {
    }

    /** {@code title}은 앱에 보이는 표시 제목(한글 제목 우선), {@code recordCount}는 기간 내 새로 생긴 시청 기록 수. */
    public record TopMovie(Long movieId, String title, String posterPath, long recordCount) {
    }
}
