package com.project.cinemory.domain.report.repository;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * M3-a 시청 분석 리포트 읽기 전용 집계(4-8-B). {@code WatchRecordRepository}(CRUD·대표 조율)와
 * 성격이 달라 별도 리포지토리로 뒀다. 전부 projection으로만 받는다 — 집계 결과는 엔티티가 아니다.
 *
 * <p>⚠️ 대부분 native query다. 윈도 함수(선호 감독 1/N 분배)와 파생 테이블은 HQL이 표현할 수
 * 없고, {@code DAYOFWEEK()}·{@code LEAST}/{@code GREATEST} 등 MySQL 전용 함수를 쓴다 — 스펙
 * 문서(4-8-C)의 SQL 블록 자체가 이미 raw SQL이다. {@code findSummary}/{@code findPeriodSummary}
 * /{@code findFirstRecordCreatedAt}만 엔티티 경로 탐색만으로 충분해 JPQL로 남겼다.
 *
 * <p>⚠️ 스펙 초안의 {@code findRatingBias(userId, minVoteCount)} 단일 메서드는 구현 중
 * 셋으로 쪼갰다 — 평균({@code ratingBiasAverage})은 하한을 걸지 않고 최댓값/최솟값
 * ({@code mostOverratedByMe}/{@code mostUnderratedByMe})만 {@code vote_count} 하한이 걸려
 * 같은 쿼리 하나로는 두 필터가 섞인다(4-5). 세 쿼리는 같은 기본 조건을 공유하고 계산 방식만 다르다.
 */
public interface ReportRepository extends Repository<WatchRecord, Long> {

    // ── 기본 지표 5종 : 한 쿼리로 묶는다 ───────────────────────────────
    @Query("""
            SELECT COUNT(DISTINCT wr.movie.id)                                  AS movieCount,
                   COUNT(wr.id)                                                 AS watchCount,
                   SUM(CASE WHEN wr.watchDate IS NULL THEN 1 ELSE 0 END)        AS undatedCount,
                   COALESCE(SUM(wr.movie.runtime), 0)                           AS totalWatchedMinutes,
                   AVG(CASE WHEN wr.representative = TRUE THEN wr.rating END)   AS averageRating
            FROM WatchRecord wr
            WHERE wr.user.id = :userId
            """)
    ReportSummaryProjection findSummary(@Param("userId") Long userId);

    // ── 선호 지표 4종 (2-4 패턴) ───────────────────────────────────────
    @Query(value = """
            SELECT g.id AS id, g.name AS name, SUM(wr.rating * mg.weight) AS score, COUNT(*) AS count
            FROM watch_record wr
            JOIN movie_genre mg ON mg.movie_id = wr.movie_id
            JOIN genre g ON g.id = mg.genre_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
            GROUP BY g.id, g.name
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PreferenceProjection> findTopGenres(@Param("userId") Long userId, @Param("limit") int limit);

    @Query(value = """
            SELECT c.id AS id, c.name AS name, SUM(wr.rating * mc.weight) AS score, COUNT(*) AS count
            FROM watch_record wr
            JOIN movie_country mc ON mc.movie_id = wr.movie_id
            JOIN country c ON c.id = mc.country_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
            GROUP BY c.id, c.name
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PreferenceProjection> findTopCountries(@Param("userId") Long userId, @Param("limit") int limit);

    // ⚠️ RoleTier.weight는 DB 컬럼이 아닌 애플리케이션 상수(LEAD 0.5/SUPPORTING 0.4/MINOR 0.1/EXTRA 0.0) —
    // enum 선언 순서를 CASE에 그대로 옮긴 것이라 RoleTier가 바뀌면 이 쿼리도 함께 바뀌어야 한다.
    @Query(value = """
            SELECT p.id AS id, p.name AS name,
                   SUM(wr.rating * CASE ma.role_tier
                       WHEN 'LEAD' THEN 0.5 WHEN 'SUPPORTING' THEN 0.4 WHEN 'MINOR' THEN 0.1 ELSE 0.0 END) AS score,
                   COUNT(*) AS count
            FROM watch_record wr
            JOIN movie_actor ma ON ma.movie_id = wr.movie_id
            JOIN person p ON p.id = ma.person_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
            GROUP BY p.id, p.name
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PreferenceProjection> findTopActors(@Param("userId") Long userId, @Param("limit") int limit);

    // 공동 연출 1/N 분배 (RA-4 C안). 컬럼 추가 없이 윈도 함수로 분모를 만든다(MySQL 8+).
    @Query(value = """
            SELECT p.id AS id, p.name AS name,
                   SUM(wr.rating / d.director_count) AS score,
                   COUNT(*) AS count
            FROM watch_record wr
            JOIN (
                SELECT md.movie_id, md.person_id,
                       COUNT(*) OVER (PARTITION BY md.movie_id) AS director_count
                FROM movie_director md
            ) d ON d.movie_id = wr.movie_id
            JOIN person p ON p.id = d.person_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
            GROUP BY p.id, p.name
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PreferenceProjection> findTopDirectors(@Param("userId") Long userId, @Param("limit") int limit);

    // ── 분포 ──────────────────────────────────────────────────────────
    // 정규화(ROUND + 1~10 클램프) — validateRating()이 1.0 단위 자체는 강제하지 않는다(4-8-C ②).
    @Query(value = """
            SELECT CAST(LEAST(10, GREATEST(1, ROUND(wr.rating))) AS UNSIGNED) AS rating, COUNT(*) AS count
            FROM watch_record wr
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
            GROUP BY rating
            """, nativeQuery = true)
    List<RatingBucketProjection> findRatingDistribution(@Param("userId") Long userId);

    // watch_type이 nullable이라 NULL이 한 그룹으로 잡힌다 — UNSPECIFIED 매핑은 Service 책임.
    @Query(value = """
            SELECT wr.watch_type AS watchType, COUNT(*) AS count
            FROM watch_record wr
            WHERE wr.user_id = :userId
            GROUP BY wr.watch_type
            """, nativeQuery = true)
    List<WatchTypeProjection> findWatchTypeDistribution(@Param("userId") Long userId);

    @Query(value = """
            SELECT op.id AS id, op.name AS name, COUNT(*) AS count
            FROM watch_record wr
            JOIN ott_platform op ON op.id = wr.ott_platform_id
            WHERE wr.user_id = :userId AND wr.watch_type = 'OTT' AND wr.ott_platform_id IS NOT NULL
            GROUP BY op.id, op.name
            ORDER BY count DESC
            """, nativeQuery = true)
    List<OttPlatformProjection> findOttPlatformDistribution(@Param("userId") Long userId);

    // 비선형 버킷 — 21세기는 뭉치고 20세기를 펼친다(4-4). release_date가 nullable이라 UNKNOWN 필요.
    @Query(value = """
            SELECT CASE
                       WHEN m.release_date IS NULL THEN 'UNKNOWN'
                       WHEN YEAR(m.release_date) >= 2020 THEN '2020s'
                       WHEN YEAR(m.release_date) >= 2010 THEN '2010s'
                       WHEN YEAR(m.release_date) >= 2000 THEN '2000s'
                       WHEN YEAR(m.release_date) >= 1990 THEN '1990s'
                       WHEN YEAR(m.release_date) >= 1980 THEN '1980s'
                       WHEN YEAR(m.release_date) >= 1970 THEN '1970s'
                       ELSE '~1960s'
                   END AS decade,
                   COUNT(DISTINCT wr.movie_id) AS count
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE
            GROUP BY decade
            ORDER BY FIELD(decade, '2020s', '2010s', '2000s', '1990s', '1980s', '1970s', '~1960s', 'UNKNOWN')
            """, nativeQuery = true)
    List<DecadeProjection> findReleaseDecadeDistribution(@Param("userId") Long userId);

    // DAYOFWEEK() 고정 — 1=일요일 ~ 7=토요일. WEEKDAY()(0=월요일)와 섞지 않는다(4-8-C ⑦).
    @Query(value = """
            SELECT DAYOFWEEK(wr.watch_date) AS weekday, COUNT(*) AS count
            FROM watch_record wr
            WHERE wr.user_id = :userId AND wr.watch_date IS NOT NULL
            GROUP BY weekday
            """, nativeQuery = true)
    List<WeekdayProjection> findWeekdayDistribution(@Param("userId") Long userId);

    // ── 시계열 ────────────────────────────────────────────────────────
    // 전 기간, 공백 달 없음 — Service가 첫 달~마지막 달 사이를 0으로 채운다(4-2).
    @Query(value = """
            SELECT YEAR(wr.watch_date)  AS year,
                   MONTH(wr.watch_date) AS month,
                   COUNT(*)                          AS watchCount,
                   COUNT(DISTINCT wr.movie_id)       AS movieCount,
                   COALESCE(SUM(m.runtime), 0)       AS watchedMinutes
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.watch_date IS NOT NULL
            GROUP BY year, month
            ORDER BY year, month
            """, nativeQuery = true)
    List<MonthlyTrendProjection> findMonthlyTrend(@Param("userId") Long userId);

    // ── 단건·목록 ─────────────────────────────────────────────────────
    @Query(value = """
            SELECT wr.movie_id AS movieId, m.title AS title, m.release_date AS releaseDate
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND m.release_date IS NOT NULL
            ORDER BY m.release_date ASC
            LIMIT 1
            """, nativeQuery = true)
    OldestWatchedProjection findOldestWatched(@Param("userId") Long userId);

    // 평균에는 vote_count 하한을 걸지 않는다 — 걸면 "내 평균 성향"이라는 말뜻에서 멀어진다(4-8-C ⑥).
    @Query(value = """
            SELECT AVG(wr.rating - m.vote_average)
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE
              AND wr.rating IS NOT NULL AND m.vote_average IS NOT NULL
            """, nativeQuery = true)
    Double findRatingBiasAverage(@Param("userId") Long userId);

    @Query(value = """
            SELECT wr.movie_id AS movieId, m.title AS title, m.poster_path AS posterPath,
                   wr.rating AS myRating, m.vote_average AS publicRating,
                   (wr.rating - m.vote_average) AS gap
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE
              AND wr.rating IS NOT NULL AND m.vote_average IS NOT NULL AND m.vote_count >= :minVoteCount
            ORDER BY gap DESC
            LIMIT 1
            """, nativeQuery = true)
    MovieRatingGapProjection findMostOverratedByMe(@Param("userId") Long userId, @Param("minVoteCount") int minVoteCount);

    @Query(value = """
            SELECT wr.movie_id AS movieId, m.title AS title, m.poster_path AS posterPath,
                   wr.rating AS myRating, m.vote_average AS publicRating,
                   (wr.rating - m.vote_average) AS gap
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.is_representative = TRUE
              AND wr.rating IS NOT NULL AND m.vote_average IS NOT NULL AND m.vote_count >= :minVoteCount
            ORDER BY gap ASC
            LIMIT 1
            """, nativeQuery = true)
    MovieRatingGapProjection findMostUnderratedByMe(@Param("userId") Long userId, @Param("minVoteCount") int minVoteCount);

    // watch_date 무관 — 날짜 없는 기록도 회차는 회차다(4-6).
    @Query(value = """
            SELECT wr.movie_id AS movieId, m.title AS title, m.poster_path AS posterPath, COUNT(*) AS watchCount
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId
            GROUP BY wr.movie_id, m.title, m.poster_path
            HAVING COUNT(*) > 1
            ORDER BY watchCount DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<RewatchProjection> findRewatchTop(@Param("userId") Long userId, @Param("limit") int limit);

    @Query("SELECT MIN(wr.createdAt) FROM WatchRecord wr WHERE wr.user.id = :userId")
    LocalDateTime findFirstRecordCreatedAt(@Param("userId") Long userId);

    // ── 기간(월간·연간) — 4-8-H ─────────────────────────────────────────
    // ⚠️ 이 섹션의 어떤 쿼리에도 is_representative를 쓰지 않는다. 대표는 가장 최근 회차로 옮겨가므로
    // 넣는 순간 미래의 재관람이 과거 기간의 리포트를 바꾼다(M3a 10-1).

    // 회차형(watchCount·totalWatchedMinutes) + 편수형(movieCount). averageRating은 일부러 뺐다 —
    // 여기서 내면 회차 평균이 되어 집계형 규칙과 어긋난다(findPeriodAverageRating).
    @Query("""
            SELECT COUNT(DISTINCT wr.movie.id)          AS movieCount,
                   COUNT(wr.id)                         AS watchCount,
                   COALESCE(SUM(wr.movie.runtime), 0)   AS totalWatchedMinutes
            FROM WatchRecord wr
            WHERE wr.user.id = :userId AND wr.watchDate BETWEEN :from AND :to
            """)
    PeriodSummaryProjection findPeriodSummary(@Param("userId") Long userId,
                                              @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 집계형 — 영화당 기간 안의 마지막 별점 회차(⑧). rating IS NOT NULL을 파생 테이블 안에 둬야
    // "별점 있는 회차 중 마지막"이 된다(밖에 두면 별점 없는 재관람이 그 영화를 탈락시킨다).
    @Query(value = """
            SELECT AVG(t.rating)
            FROM (
                SELECT wr.rating,
                       ROW_NUMBER() OVER (PARTITION BY wr.movie_id
                                          ORDER BY wr.watch_date DESC, wr.id DESC) AS rn
                FROM watch_record wr
                WHERE wr.user_id = :userId AND wr.rating IS NOT NULL
                  AND wr.watch_date BETWEEN :from AND :to
            ) t
            WHERE t.rn = 1
            """, nativeQuery = true)
    Double findPeriodAverageRating(@Param("userId") Long userId,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query(value = """
            SELECT CAST(LEAST(10, GREATEST(1, ROUND(t.rating))) AS UNSIGNED) AS rating, COUNT(*) AS count
            FROM (
                SELECT wr.rating,
                       ROW_NUMBER() OVER (PARTITION BY wr.movie_id
                                          ORDER BY wr.watch_date DESC, wr.id DESC) AS rn
                FROM watch_record wr
                WHERE wr.user_id = :userId AND wr.rating IS NOT NULL
                  AND wr.watch_date BETWEEN :from AND :to
            ) t
            WHERE t.rn = 1
            GROUP BY rating
            """, nativeQuery = true)
    List<RatingBucketProjection> findPeriodRatingDistribution(@Param("userId") Long userId,
                                                              @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query(value = """
            SELECT wr.watch_type AS watchType, COUNT(*) AS count
            FROM watch_record wr
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY wr.watch_type
            """, nativeQuery = true)
    List<WatchTypeProjection> findPeriodWatchTypeDistribution(@Param("userId") Long userId,
                                                              @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 편수형(⑩) — 별점 무관, 같은 영화 재관람은 한 편. 공동 연출은 감독마다 한 편씩(1/N 분배 없음).
    // 동률은 id 오름차순으로 결정적 정렬.
    @Query(value = """
            SELECT p.id AS id, p.name AS name, COUNT(DISTINCT wr.movie_id) AS count
            FROM watch_record wr
            JOIN movie_director md ON md.movie_id = wr.movie_id
            JOIN person p ON p.id = md.person_id
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY p.id, p.name
            ORDER BY count DESC, p.id
            LIMIT 1
            """, nativeQuery = true)
    MostWatchedProjection findMostWatchedDirector(@Param("userId") Long userId,
                                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 가중치 없이 세므로 LEAD·SUPPORTING(D-1 절대 순번 0~9)만 — 단역 세 편이 주연 세 편과 같은 순위가 되지 않도록.
    @Query(value = """
            SELECT p.id AS id, p.name AS name, COUNT(DISTINCT wr.movie_id) AS count
            FROM watch_record wr
            JOIN movie_actor ma ON ma.movie_id = wr.movie_id
            JOIN person p ON p.id = ma.person_id
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
              AND ma.role_tier IN ('LEAD', 'SUPPORTING')
            GROUP BY p.id, p.name
            ORDER BY count DESC, p.id
            LIMIT 1
            """, nativeQuery = true)
    MostWatchedProjection findMostWatchedActor(@Param("userId") Long userId,
                                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    // weight를 쓰지 않는다 — 누적 topGenres(score)와 다른 지표다(RA-4 이름 분리).
    @Query(value = """
            SELECT g.id AS id, g.name AS name, COUNT(DISTINCT wr.movie_id) AS count
            FROM watch_record wr
            JOIN movie_genre mg ON mg.movie_id = wr.movie_id
            JOIN genre g ON g.id = mg.genre_id
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY g.id, g.name
            ORDER BY count DESC, g.id
            LIMIT :limit
            """, nativeQuery = true)
    List<MostWatchedProjection> findMostWatchedGenres(@Param("userId") Long userId,
                                                      @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                      @Param("limit") int limit);

    @Query(value = """
            SELECT c.id AS id, c.name AS name, COUNT(DISTINCT wr.movie_id) AS count
            FROM watch_record wr
            JOIN movie_country mc ON mc.movie_id = wr.movie_id
            JOIN country c ON c.id = mc.country_id
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY c.id, c.name
            ORDER BY count DESC, c.id
            LIMIT :limit
            """, nativeQuery = true)
    List<MostWatchedProjection> findMostWatchedCountries(@Param("userId") Long userId,
                                                         @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                         @Param("limit") int limit);

    // 월간 전용 한 줄 문구 — 연간은 weekdayDistribution(7개 고정)으로 승격했다.
    @Query(value = """
            SELECT DAYOFWEEK(wr.watch_date) AS weekday
            FROM watch_record wr
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY weekday
            ORDER BY COUNT(*) DESC
            LIMIT 1
            """, nativeQuery = true)
    Integer findMostWatchedWeekday(@Param("userId") Long userId,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 누적 findMonthlyTrend + 구간 조건. 공백 달 채우기는 Service(1~12월 고정).
    @Query(value = """
            SELECT YEAR(wr.watch_date)  AS year,
                   MONTH(wr.watch_date) AS month,
                   COUNT(*)                          AS watchCount,
                   COUNT(DISTINCT wr.movie_id)       AS movieCount,
                   COALESCE(SUM(m.runtime), 0)       AS watchedMinutes
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY year, month
            ORDER BY year, month
            """, nativeQuery = true)
    List<MonthlyTrendProjection> findPeriodMonthlyTrend(@Param("userId") Long userId,
                                                        @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 누적 findWeekdayDistribution + 구간 조건. 빈 요일 채우기는 Service(1~7 고정).
    @Query(value = """
            SELECT DAYOFWEEK(wr.watch_date) AS weekday, COUNT(*) AS count
            FROM watch_record wr
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            GROUP BY weekday
            """, nativeQuery = true)
    List<WeekdayProjection> findPeriodWeekdayDistribution(@Param("userId") Long userId,
                                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 목록형(⑨) — 기간 안에 5점(rating = 10) 회차가 하나라도 있으면 포함. 등호 비교는 v16 DECIMAL(3,1)이라 안전.
    // fiveStarDate = 그해 처음 5점을 준 날, 그 날짜 오름차순. 전량 반환(LIMIT 없음).
    @Query(value = """
            SELECT wr.movie_id AS movieId, m.title AS title, m.poster_path AS posterPath,
                   MIN(wr.watch_date) AS fiveStarDate
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.rating = 10
              AND wr.watch_date BETWEEN :from AND :to
            GROUP BY wr.movie_id, m.title, m.poster_path
            ORDER BY fiveStarDate, wr.movie_id
            """, nativeQuery = true)
    List<FiveStarMovieProjection> findFiveStarMovies(@Param("userId") Long userId,
                                                     @Param("from") LocalDate from, @Param("to") LocalDate to);

    // ── 캘린더 ────────────────────────────────────────────────────────
    // 하루 여러 편이 가능하므로 날짜별 그룹핑은 Service가 한다.
    @Query(value = """
            SELECT wr.id AS recordId, wr.movie_id AS movieId, m.title AS title, m.poster_path AS posterPath,
                   wr.rating AS rating, wr.watch_date AS watchDate
            FROM watch_record wr
            JOIN movie m ON m.id = wr.movie_id
            WHERE wr.user_id = :userId AND wr.watch_date BETWEEN :from AND :to
            ORDER BY wr.watch_date ASC, wr.id ASC
            """, nativeQuery = true)
    List<CalendarRecordProjection> findCalendarRecords(@Param("userId") Long userId,
                                                          @Param("from") LocalDate from, @Param("to") LocalDate to);
}
