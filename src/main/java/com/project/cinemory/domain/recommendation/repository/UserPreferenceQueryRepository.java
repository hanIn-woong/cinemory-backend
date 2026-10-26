package com.project.cinemory.domain.recommendation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 선호 프로필용 집계 쿼리 (M3b-recommendation-spec S-1-1 · S-1-6).
 *
 * <p>집계 대상은 M3-a RA-1과 같다 — <b>대표 기록 + {@code rating IS NOT NULL}</b>. 조인·가중치 형태는 {@code ReportRepository}의
 * 선호 쿼리와 같지만 점수식이 다르다: 리포트의 장르·국가는 합계형이고 여기서는 네 축 모두 평균 중심 합
 * {@code Σ(별점 − m)·w}다(리포트는 바꾸지 않는다). 양수인 항목만 돌려주고 정규화는 {@code ProfileCalculator}가 한다.
 *
 * <p>⚠️ 배우 가중치 CASE는 {@code RoleTier}(LEAD 0.5 / SUPPORTING 0.4 / MINOR 0.1)를 옮긴 것이다. RoleTier가 바뀌면
 * 이 쿼리도 함께 바뀌어야 한다 — 리포트 쿼리와 같은 제약이다.
 */
@Repository
public class UserPreferenceQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /** 사용자 누적 평균 별점 m — 대표 기록의 별점 평균(M3-a statistics.averageRating과 같은 정의). 없으면 {@code null}. */
    @Transactional(readOnly = true)
    public Double findMeanRating(long userId) {
        Object result = em.createNativeQuery("""
                        SELECT AVG(rating) FROM watch_record
                        WHERE user_id = :userId AND is_representative = TRUE AND rating IS NOT NULL
                        """)
                .setParameter("userId", userId)
                .getSingleResult();
        return result == null ? null : ((Number) result).doubleValue();
    }

    @Transactional(readOnly = true)
    public Map<Long, Double> findGenreScores(long userId, double mean) {
        return scores("""
                SELECT mg.genre_id AS id, SUM((wr.rating - :m) * mg.weight) AS score
                FROM watch_record wr
                JOIN movie_genre mg ON mg.movie_id = wr.movie_id
                WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
                GROUP BY mg.genre_id
                """, userId, mean, null);
    }

    @Transactional(readOnly = true)
    public Map<Long, Double> findCountryScores(long userId, double mean) {
        return scores("""
                SELECT mc.country_id AS id, SUM((wr.rating - :m) * mc.weight) AS score
                FROM watch_record wr
                JOIN movie_country mc ON mc.movie_id = wr.movie_id
                WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
                GROUP BY mc.country_id
                """, userId, mean, null);
    }

    /** 공동 연출 1/N 분배(RA-4)가 w. 감독은 애니메이션을 빼지 않는다(M3-a 11-2). */
    @Transactional(readOnly = true)
    public Map<Long, Double> findDirectorScores(long userId, double mean) {
        return scores("""
                SELECT d.person_id AS id, SUM((wr.rating - :m) / d.director_count) AS score
                FROM watch_record wr
                JOIN (
                    SELECT md.movie_id, md.person_id, COUNT(*) OVER (PARTITION BY md.movie_id) AS director_count
                    FROM movie_director md
                ) d ON d.movie_id = wr.movie_id
                WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
                GROUP BY d.person_id
                """, userId, mean, null);
    }

    /** 배우 — EXTRA(w=0)와 애니메이션(성우)을 뺀다(M3-a 11-2). */
    @Transactional(readOnly = true)
    public Map<Long, Double> findActorScores(long userId, double mean, int animationTmdbGenreId) {
        return scores("""
                SELECT t.person_id AS id, SUM((wr.rating - :m) * t.w) AS score
                FROM watch_record wr
                JOIN (
                    SELECT ma.movie_id, ma.person_id,
                           CASE ma.role_tier WHEN 'LEAD' THEN 0.5 WHEN 'SUPPORTING' THEN 0.4 WHEN 'MINOR' THEN 0.1 END AS w
                    FROM movie_actor ma
                    WHERE ma.role_tier IN ('LEAD', 'SUPPORTING', 'MINOR')
                ) t ON t.movie_id = wr.movie_id
                WHERE wr.user_id = :userId AND wr.is_representative = TRUE AND wr.rating IS NOT NULL
                  AND NOT EXISTS (
                        SELECT 1 FROM movie_genre mg JOIN genre g ON g.id = mg.genre_id
                        WHERE mg.movie_id = wr.movie_id AND g.tmdb_genre_id = :animationGenreId)
                GROUP BY t.person_id
                """, userId, mean, animationTmdbGenreId);
    }

    /** 시청 기록이 있는 영화 — 전 회차 기준, 대표 여부 무관(제외 규칙). */
    @Transactional(readOnly = true)
    public Set<Long> findWatchedMovieIds(long userId) {
        return toIdSet(em.createNativeQuery("SELECT DISTINCT movie_id FROM watch_record WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getResultList());
    }

    @Transactional(readOnly = true)
    public Set<Long> findWishedMovieIds(long userId) {
        return toIdSet(em.createNativeQuery("SELECT movie_id FROM wish_movie WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getResultList());
    }

    private Map<Long, Double> scores(String sql, long userId, double mean, Integer animationGenreId) {
        var query = em.createNativeQuery("SELECT s.id, s.score FROM (" + sql + ") s WHERE s.score > 0")
                .setParameter("userId", userId)
                .setParameter("m", BigDecimal.valueOf(mean));
        if (animationGenreId != null) {
            query.setParameter("animationGenreId", animationGenreId);
        }
        Map<Long, Double> result = new HashMap<>();
        for (Object row : query.getResultList()) {
            Object[] columns = (Object[]) row;
            result.put(((Number) columns[0]).longValue(), ((Number) columns[1]).doubleValue());
        }
        return result;
    }

    private static Set<Long> toIdSet(List<?> rows) {
        Set<Long> ids = new HashSet<>();
        for (Object row : rows) {
            ids.add(((Number) row).longValue());
        }
        return ids;
    }
}
