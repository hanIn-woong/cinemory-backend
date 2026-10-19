package com.project.cinemory.domain.admin.repository;

import com.project.cinemory.domain.admin.dto.AdminDashboardResponse.TopMovie;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 집계 쿼리(admin-spec 13-1) — 지표별로 {@code GROUP BY DATE(created_at)} 쿼리 한 개씩.
 * 엔티티가 아니라 집계 결과라 네이티브로 직접 읽는다. 기간은 {@code [from, toExclusive)} 반열린 구간이다.
 * 테이블이 작아 캐시도 인덱스도 두지 않는다(스키마 변경 없음). 쿼리마다 테이블이 달라 SQL을 상수로 고정한다 —
 * 식별자를 문자열로 이어 붙이지 않는다.
 */
@Repository
public class AdminDashboardRepository {

    private static final String DAILY_NEW_USERS =
            "SELECT DATE(created_at), COUNT(*) FROM user WHERE created_at >= :from AND created_at < :to GROUP BY DATE(created_at)";
    // 활성 사용자의 근사치(BD-9): 리프레시 토큰이 발급된 사용자 — 로그인과 재발급 모두 새 토큰 행을 만든다
    private static final String DAILY_ACTIVE_USERS =
            "SELECT DATE(created_at), COUNT(DISTINCT user_id) FROM refresh_token WHERE created_at >= :from AND created_at < :to GROUP BY DATE(created_at)";
    private static final String DAILY_NEW_WATCH_RECORDS =
            "SELECT DATE(created_at), COUNT(*) FROM watch_record WHERE created_at >= :from AND created_at < :to GROUP BY DATE(created_at)";
    private static final String DAILY_NEW_REVIEWS =
            "SELECT DATE(created_at), COUNT(*) FROM review WHERE created_at >= :from AND created_at < :to GROUP BY DATE(created_at)";
    private static final String DAILY_NEW_MOVIES =
            "SELECT DATE(created_at), COUNT(*) FROM movie WHERE created_at >= :from AND created_at < :to GROUP BY DATE(created_at)";

    private static final String ACTIVE_USERS =
            "SELECT COUNT(DISTINCT user_id) FROM refresh_token WHERE created_at >= :from AND created_at < :to";
    private static final String RECORDING_USERS =
            "SELECT COUNT(DISTINCT user_id) FROM watch_record WHERE created_at >= :from AND created_at < :to";

    // 동률은 movie_id 오름차순으로 결정적 정렬. 제목은 앱에 보이는 표시 제목(한글 제목 우선).
    private static final String TOP_MOVIES = """
            SELECT wr.movie_id, COALESCE(m.title_ko, m.title), m.poster_path, COUNT(*) AS cnt
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.created_at >= :from AND wr.created_at < :to
            GROUP BY wr.movie_id, m.title, m.title_ko, m.poster_path
            ORDER BY cnt DESC, wr.movie_id ASC
            LIMIT :limit
            """;

    @PersistenceContext
    private EntityManager em;

    public Map<LocalDate, Long> dailyNewUsers(LocalDateTime from, LocalDateTime toExclusive) {
        return daily(DAILY_NEW_USERS, from, toExclusive);
    }

    public Map<LocalDate, Long> dailyActiveUsers(LocalDateTime from, LocalDateTime toExclusive) {
        return daily(DAILY_ACTIVE_USERS, from, toExclusive);
    }

    public Map<LocalDate, Long> dailyNewWatchRecords(LocalDateTime from, LocalDateTime toExclusive) {
        return daily(DAILY_NEW_WATCH_RECORDS, from, toExclusive);
    }

    public Map<LocalDate, Long> dailyNewReviews(LocalDateTime from, LocalDateTime toExclusive) {
        return daily(DAILY_NEW_REVIEWS, from, toExclusive);
    }

    public Map<LocalDate, Long> dailyNewMovies(LocalDateTime from, LocalDateTime toExclusive) {
        return daily(DAILY_NEW_MOVIES, from, toExclusive);
    }

    public long activeUsers(LocalDateTime from, LocalDateTime toExclusive) {
        return count(ACTIVE_USERS, from, toExclusive);
    }

    public long recordingUsers(LocalDateTime from, LocalDateTime toExclusive) {
        return count(RECORDING_USERS, from, toExclusive);
    }

    @SuppressWarnings("unchecked")
    public List<TopMovie> topMovies(LocalDateTime from, LocalDateTime toExclusive, int limit) {
        List<Object[]> rows = em.createNativeQuery(TOP_MOVIES)
                .setParameter("from", from).setParameter("to", toExclusive).setParameter("limit", limit)
                .getResultList();
        return rows.stream()
                .map(r -> new TopMovie(((Number) r[0]).longValue(), (String) r[1], (String) r[2], ((Number) r[3]).longValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<LocalDate, Long> daily(String sql, LocalDateTime from, LocalDateTime toExclusive) {
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("from", from).setParameter("to", toExclusive).getResultList();
        Map<LocalDate, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put(toLocalDate(row[0]), ((Number) row[1]).longValue());
        }
        return result;
    }

    private long count(String sql, LocalDateTime from, LocalDateTime toExclusive) {
        Object value = em.createNativeQuery(sql)
                .setParameter("from", from).setParameter("to", toExclusive).getSingleResult();
        return ((Number) value).longValue();
    }

    /** {@code DATE(...)}는 드라이버·하이버네이트 버전에 따라 {@code LocalDate}·{@code java.sql.Date}로 온다. */
    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }
}
