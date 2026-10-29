package com.project.cinemory.domain.feed.repository;

import com.project.cinemory.domain.feed.entity.ActivityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 피드 네이티브 쿼리 모음 (social-feed-spec 4-1 ~ 4-3). 조회와 조립을 분리한다 — 여기서는 키만 돌려주고
 * 엔티티·DTO 조립은 {@code ActivityAssembler}가 한다.
 *
 * <p>여섯 갈래 {@code UNION ALL}은 {@link #activityUnion}<b>한 곳</b>에서만 만든다. 스트립 집계·프레임·개인 피드가 같은 조각을
 * 쓰므로 갈래마다 조건이 어긋나는 일이 없다. 모든 갈래에 {@code created_at <= :asOf}가 들어간다(SF-7).
 *
 * <p>⚠️ {@link #findViewableFollowingIds}의 공개 범위 조건은 {@code UserAccessPolicy.canView}를 SQL로 옮긴 것이다.
 * 정책이 바뀌면 함께 바뀌어야 한다 — 테스트 T-3이 둘을 대조한다.
 */
@Repository
public class FeedQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /** 갈래 안 {@code created_at} 경계. */
    enum Range {
        /** 경계 없음 — 사람별 최댓값을 사람당 탐색 1회(고전 loose index scan)로 구한다. 첫 페이지 순위 전용(4-2). */
        NONE,
        /** {@code created_at <= :asOf} — 개인 피드. */
        AS_OF,
        /** {@code created_at >= :since AND created_at <= :asOf} — 프레임·캐시 미스 대체 집계. */
        SINCE_AS_OF
    }

    /**
     * 활동 합성 SQL 조각 (4-1). 바인딩 파라미터: {@code :users}(IN 목록) · {@code :asOf} · ({@code bounded}이면) {@code :since}.
     *
     * @param maxMode {@code false}면 원시 행 {@code (user_id, sort_key, source_id, occurred_at, group_date)} — 프레임·개인 피드.
     *                {@code true}면 갈래 <b>안에서</b> {@code GROUP BY user_id}로 먼저 묶은 {@code (user_id, last_at)} — 스트립 집계.
     *                원시 행을 합친 뒤 바깥에서만 묶으면 기간 내 행 전부가 파생 테이블에 실체화된다.
     * @param bounded 기간 하한 {@code :since}을 거는지 (개인 피드는 기간 없음)
     */
    public static String activityUnion(boolean maxMode, boolean bounded) {
        return activityUnion(maxMode, bounded ? Range.SINCE_AS_OF : Range.AS_OF);
    }

    static String activityUnion(boolean maxMode, Range range) {
        return activityUnion(maxMode, range, false);
    }

    /**
     * @param withMovie 원시 행 모드에서 {@code movie_id} 열을 덧붙인다({@code WATCH_RECORD}만 값, 나머지 {@code NULL}) — 스트립 프레임의
     *                  영화 단위 합치기용(4-1 ③). 최댓값 모드에서는 무시한다.
     */
    static String activityUnion(boolean maxMode, Range range, boolean withMovie) {
        boolean movie = withMovie && !maxMode;
        List<String> branches = List.of(
                simpleBranch(maxMode, range, "watch_record", "wr", "user_id", ActivityType.WATCH_RECORD, movie ? "wr.movie_id" : null, movie),
                simpleBranch(maxMode, range, "review", "rv", "user_id", ActivityType.REVIEW, null, movie),
                simpleBranch(maxMode, range, "wish_movie", "wm", "user_id", ActivityType.WISH, null, movie),
                simpleBranch(maxMode, range, "collection", "c", "user_id", ActivityType.COLLECTION_CREATED, null, movie),
                collectionMoviesAddedBranch(maxMode, range, movie),
                simpleBranch(maxMode, range, "follow", "f", "follower_id", ActivityType.FOLLOW, null, movie));
        return String.join("\nUNION ALL\n", branches);
    }

    /**
     * 첫 페이지 순위 SQL (4-2) — 대상 전원의 {@code (user_id, last_at)}를 최근순으로, LIMIT 없이. 갈래 안에 {@code since}·{@code asOf}를 걸지 않아
     * 사람당 탐색이 1회다. 30일 조건은 바깥 {@code HAVING}이다. 바인딩: {@code :users} · {@code :since}.
     */
    public static String stripOrderSql() {
        return "SELECT t.user_id, MAX(t.last_at) AS last_at FROM (\n" + activityUnion(true, Range.NONE) + "\n) t\n"
                + "GROUP BY t.user_id\n"
                + "HAVING MAX(t.last_at) >= :since\n"
                + "ORDER BY last_at DESC, t.user_id DESC";
    }

    /** 스트립 집계 SQL (4-2 ②). 측정 ①이 같은 문장으로 {@code EXPLAIN ANALYZE}를 하도록 공개한다. */
    public static String stripsSql(boolean paged) {
        return "SELECT t.user_id, MAX(t.last_at) AS last_at FROM (\n" + activityUnion(true, true) + "\n) t\n"
                + "GROUP BY t.user_id\n"
                + (paged ? "HAVING MAX(t.last_at) < :at OR (MAX(t.last_at) = :at AND t.user_id < :uid)\n" : "")
                + "ORDER BY last_at DESC, t.user_id DESC";
    }

    /** 프레임 SQL (4-2 ③) — 영화 단위 합치기를 적용한다(4-1 ③). */
    public static String framesSql(boolean bounded, boolean withSeenAfter) {
        return framesSql(bounded, withSeenAfter, true);
    }

    /**
     * @param dedupeWatch {@code true}면 {@code <= 6} 전에 {@code WATCH_RECORD}를 {@code (user_id, movie_id)}별 최신 1건만 남긴다.
     *                    {@code newCount}는 합치기 앞 단계(안쪽 SELECT)에서 세므로 합치기 전 원시 활동 수다.
     */
    public static String framesSql(boolean bounded, boolean withSeenAfter, boolean dedupeWatch) {
        String newCount = withSeenAfter
                ? ", COUNT(CASE WHEN u.occurred_at > :seenAfter THEN 1 END) OVER (PARTITION BY u.user_id) AS new_count"
                : ", 0 AS new_count";
        Range range = bounded ? Range.SINCE_AS_OF : Range.AS_OF;
        if (!dedupeWatch) {
            return "SELECT x.user_id, x.sort_key, x.source_id, x.occurred_at, x.group_date, x.new_count FROM (\n"
                    + "SELECT u.user_id, u.sort_key, u.source_id, u.occurred_at, u.group_date,\n"
                    + "ROW_NUMBER() OVER (PARTITION BY u.user_id ORDER BY u.occurred_at DESC, u.sort_key DESC, u.source_id DESC) AS rn"
                    + newCount + "\nFROM (\n" + activityUnion(false, range) + "\n) u\n) x\n"
                    + "WHERE x.rn <= :frameCount ORDER BY x.user_id, x.rn";
        }
        // d: 원시 행 + newCount(원시 기준) + 영화별 최신 순번 → x: 합친 뒤 사용자별 순번 → 바깥: 상위 frameCount
        return "SELECT y.user_id, y.sort_key, y.source_id, y.occurred_at, y.group_date, y.new_count FROM (\n"
                + "SELECT x.user_id, x.sort_key, x.source_id, x.occurred_at, x.group_date, x.new_count,\n"
                + "ROW_NUMBER() OVER (PARTITION BY x.user_id ORDER BY x.occurred_at DESC, x.sort_key DESC, x.source_id DESC) AS rn FROM (\n"
                + "SELECT d.user_id, d.sort_key, d.source_id, d.occurred_at, d.group_date, d.new_count, d.movie_id,\n"
                + "ROW_NUMBER() OVER (PARTITION BY d.user_id, d.movie_id ORDER BY d.occurred_at DESC, d.source_id DESC) AS movie_rn FROM (\n"
                + "SELECT u.user_id, u.sort_key, u.source_id, u.occurred_at, u.group_date, u.movie_id" + newCount + "\nFROM (\n"
                + activityUnion(false, range, true) + "\n) u\n) d\n"
                + ") x\nWHERE x.movie_id IS NULL OR x.movie_rn = 1\n"
                + ") y\nWHERE y.rn <= :frameCount ORDER BY y.user_id, y.rn";
    }

    /** 개인 피드 SQL (4-3). 키셋 조건은 풀어 쓴 OR다. */
    public static String activitiesSql(boolean paged) {
        return "SELECT a.user_id, a.sort_key, a.source_id, a.occurred_at, a.group_date FROM (\n"
                + activityUnion(false, false) + "\n) a\n"
                + (paged ? "WHERE a.occurred_at < :at OR (a.occurred_at = :at AND (a.sort_key < :k OR (a.sort_key = :k AND a.source_id < :id)))\n" : "")
                + "ORDER BY a.occurred_at DESC, a.sort_key DESC, a.source_id DESC";
    }

    private static String simpleBranch(boolean maxMode, Range range, String table, String alias, String userColumn,
                                       ActivityType type, String movieColumn, boolean withMovie) {
        String user = alias + "." + userColumn;
        String created = alias + ".created_at";
        String from = " FROM " + table + " " + alias + " WHERE " + user + " IN (:users)" + range(created, range);
        if (maxMode) {
            return "SELECT " + user + " AS user_id, MAX(" + created + ") AS last_at" + from + " GROUP BY " + user;
        }
        return "SELECT " + user + " AS user_id, " + type.sortKey() + " AS sort_key, " + alias + ".id AS source_id, "
                + created + " AS occurred_at, NULL AS group_date"
                + (withMovie ? ", " + (movieColumn != null ? movieColumn : "NULL") + " AS movie_id" : "") + from;
    }

    /**
     * {@code COLLECTION_MOVIES_ADDED} — (컬렉션, 날짜)당 하나. 컬렉션을 만든 날 추가한 영화는 {@code COLLECTION_CREATED}가
     * 지금 포스터로 흡수하므로 뺀다(2절). 최댓값 모드의 {@code MAX}는 그룹별 MAX의 MAX라 원시 모드와 같은 값이다.
     */
    private static String collectionMoviesAddedBranch(boolean maxMode, Range range, boolean withMovie) {
        String from = " FROM collection_movie cm JOIN collection c ON c.id = cm.collection_id"
                + " WHERE c.user_id IN (:users)" + range("cm.created_at", range)
                + " AND DATE(cm.created_at) <> DATE(c.created_at)";
        if (maxMode) {
            return "SELECT c.user_id AS user_id, MAX(cm.created_at) AS last_at" + from + " GROUP BY c.user_id";
        }
        return "SELECT c.user_id AS user_id, " + ActivityType.COLLECTION_MOVIES_ADDED.sortKey() + " AS sort_key, c.id AS source_id,"
                + " MAX(cm.created_at) AS occurred_at, DATE(cm.created_at) AS group_date"
                + (withMovie ? ", NULL AS movie_id" : "") + from
                + " GROUP BY c.user_id, c.id, DATE(cm.created_at)";
    }

    private static String range(String createdColumn, Range range) {
        return switch (range) {
            case NONE -> "";
            case AS_OF -> " AND " + createdColumn + " <= :asOf";
            case SINCE_AS_OF -> " AND " + createdColumn + " >= :since AND " + createdColumn + " <= :asOf";
        };
    }

    /** 내가 팔로우한 사람 중 내가 볼 수 있는 사람 (4-2 ①) — SQL로 거른다(뒤에서 거르면 페이지가 비는 문제가 생긴다). */
    @Transactional(readOnly = true)
    public List<Long> findViewableFollowingIds(long viewerId) {
        List<?> rows = em.createNativeQuery("""
                        SELECT f.following_id FROM follow f JOIN `user` u ON u.id = f.following_id
                         WHERE f.follower_id = :viewer
                           AND (u.privacy_setting = 'PUBLIC'
                                OR (u.privacy_setting = 'FRIENDS'
                                    AND EXISTS (SELECT 1 FROM follow b WHERE b.follower_id = u.id AND b.following_id = :viewer)))
                        """)
                .setParameter("viewer", viewerId)
                .getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(((Number) row).longValue());
        }
        return ids;
    }

    /**
     * 팔로잉 스트립의 대상 전원 순서 (4-2 첫 페이지) — {@code HAVING MAX(last_at) >= :since}. {@code asOf} 경계는 걸지 않는다:
     * 첫 페이지의 {@code asOf}는 방금 정한 현재 시각이라 그 이후 활동이 사실상 없다.
     */
    @Transactional(readOnly = true)
    public List<StripRow> findStripOrder(Collection<Long> users, LocalDateTime since) {
        List<StripRow> result = new ArrayList<>();
        for (Object row : em.createNativeQuery(stripOrderSql())
                .setParameter("users", users)
                .setParameter("since", since)
                .getResultList()) {
            Object[] cols = (Object[]) row;
            result.add(new StripRow(((Number) cols[0]).longValue(), toDateTime(cols[1])));
        }
        return result;
    }

    /**
     * 팔로잉 스트립 후보 (4-2 ②) — 사람별 마지막 활동. <b>캐시 미스 대체 경로</b>(기간 경계 쿼리). {@code lastBefore}/{@code userBefore}가 {@code null}이면 첫 페이지.
     * 커서: {@code last < :at OR (last = :at AND user_id < :uid)}.
     */
    @Transactional(readOnly = true)
    public List<StripRow> findStrips(Collection<Long> users, LocalDateTime since, LocalDateTime asOf,
                                     LocalDateTime lastBefore, Long userBefore, int limit) {
        boolean paged = lastBefore != null;
        Query query = em.createNativeQuery(stripsSql(paged))
                .setParameter("users", users)
                .setParameter("since", since)
                .setParameter("asOf", asOf)
                .setMaxResults(limit);
        if (paged) {
            query.setParameter("at", lastBefore).setParameter("uid", userBefore);
        }
        List<StripRow> result = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] cols = (Object[]) row;
            result.add(new StripRow(((Number) cols[0]).longValue(), toDateTime(cols[1])));
        }
        return result;
    }

    /**
     * 프레임 (4-2 ③) — 사람마다 최근 {@code frameCount}개. {@code since}가 있으면 스트립 자격과 같은 범위를 건다.
     * {@code seenAfter}가 있으면 같은 창에서 그 이후 활동 수({@code newCount})를 함께 계산한다.
     */
    @Transactional(readOnly = true)
    public List<FrameRow> findFrames(Collection<Long> users, LocalDateTime since, LocalDateTime asOf,
                                     LocalDateTime seenAfter, int frameCount) {
        boolean bounded = since != null;
        Query query = em.createNativeQuery(framesSql(bounded, seenAfter != null))
                .setParameter("users", users)
                .setParameter("asOf", asOf)
                .setParameter("frameCount", frameCount);
        if (bounded) {
            query.setParameter("since", since);
        }
        if (seenAfter != null) {
            query.setParameter("seenAfter", seenAfter);
        }
        List<FrameRow> result = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] cols = (Object[]) row;
            result.add(new FrameRow(toActivityRow(cols), ((Number) cols[5]).intValue()));
        }
        return result;
    }

    /**
     * 개인 티켓 피드 (4-3) — 기간 없음. 키셋 조건은 행 생성자 비교가 인덱스 범위로 풀리지 않는 경우가 있어 풀어 쓴 OR다.
     * {@code at}이 {@code null}이면 첫 페이지.
     */
    @Transactional(readOnly = true)
    public List<ActivityRow> findActivities(long userId, LocalDateTime asOf, LocalDateTime at, Integer sortKey,
                                            Long sourceId, int limit) {
        boolean paged = at != null;
        Query query = em.createNativeQuery(activitiesSql(paged))
                .setParameter("users", List.of(userId))
                .setParameter("asOf", asOf)
                .setMaxResults(limit);
        if (paged) {
            query.setParameter("at", at).setParameter("k", sortKey).setParameter("id", sourceId);
        }
        List<ActivityRow> result = new ArrayList<>();
        for (Object row : query.getResultList()) {
            result.add(toActivityRow((Object[]) row));
        }
        return result;
    }

    /**
     * {@code COLLECTION_MOVIES_ADDED} 그룹 조립용 (4-4) — 컬렉션들에서 {@code [from, to)}에 추가된 영화. 한 번에 읽고 날짜로 나눈다.
     * 최근 추가 순으로 돌려주므로 앞에서부터 포스터를 고르면 된다.
     */
    @Transactional(readOnly = true)
    public List<AddedMovieRow> findAddedMovies(Collection<Long> collectionIds, LocalDateTime from, LocalDateTime to) {
        List<AddedMovieRow> result = new ArrayList<>();
        for (Object row : em.createNativeQuery("""
                        SELECT cm.collection_id, DATE(cm.created_at) AS added_date, m.poster_path
                          FROM collection_movie cm JOIN movie m ON m.id = cm.movie_id
                         WHERE cm.collection_id IN (:collectionIds) AND cm.created_at >= :from AND cm.created_at < :to
                         ORDER BY cm.created_at DESC, cm.id DESC
                        """)
                .setParameter("collectionIds", collectionIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()) {
            Object[] cols = (Object[]) row;
            result.add(new AddedMovieRow(((Number) cols[0]).longValue(), toDate(cols[1]), (String) cols[2]));
        }
        return result;
    }

    private static ActivityRow toActivityRow(Object[] cols) {
        return new ActivityRow(
                ((Number) cols[0]).longValue(),
                ActivityType.fromSortKey(((Number) cols[1]).intValue()),
                ((Number) cols[2]).longValue(),
                toDateTime(cols[3]),
                toDate(cols[4]));
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return ((Timestamp) value).toLocalDateTime();
    }

    private static LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return ((java.sql.Date) value).toLocalDate();
    }
}
