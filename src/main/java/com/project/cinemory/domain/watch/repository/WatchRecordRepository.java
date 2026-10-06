package com.project.cinemory.domain.watch.repository;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WatchRecordRepository extends JpaRepository<WatchRecord, Long> {

    Optional<WatchRecord> findByUserIdAndMovieIdAndRepresentativeTrue(Long userId, Long movieId);

    // 대표 삭제 후 재선정 대상 조회 겸, 특정 영화의 전체 시청 기록(회차별) 조회에도 사용
    List<WatchRecord> findByUserIdAndMovieIdOrderByIdDesc(Long userId, Long movieId);

    // "내 영화" 목록 — 대표 기록만, movie는 @EntityGraph로 함께 로딩(N+1 회피).
    // 정렬은 메서드명에 고정하지 않고 Service가 RecordSort로 만든 Pageable에 싣는다.
    @EntityGraph(attributePaths = "movie")
    Page<WatchRecord> findByUserIdAndRepresentativeTrue(Long userId, Pageable pageable);

    // 영화 상세의 CineMory 평점 (4-2-A). 사용자당 1값을 2단계 폴백으로 고른 뒤 평균한다.
    // rating IS NOT NULL로 먼저 거르고 "대표 우선 → id DESC"로 1등을 뽑으면
    // ReviewRepository.findResolvedRatingsByReviewIds의 COALESCE(rep.rating, fallback.rating)와 같은 값이 된다.
    //   - 대표에 별점 있음   → 대표가 1등
    //   - 대표의 별점 null  → 대표가 WHERE에서 빠지고, 별점 있는 최신 기록이 1등
    //   - 별점 기록이 없음   → 그 사용자는 행이 없다 (count에서 빠진다)
    // 집계 쿼리라 행이 없어도 항상 1행(AVG = NULL, COUNT = 0)을 돌려준다.
    // ⚠️ native — JPQL은 윈도 함수를 지원하지 않는다 (4-5-A 미리보기 포스터와 같은 이유).
    @Query(value = """
            SELECT ROUND(AVG(t.rating), 2) AS average, COUNT(*) AS count
            FROM (
                SELECT wr.rating,
                       ROW_NUMBER() OVER (PARTITION BY wr.user_id
                                          ORDER BY wr.is_representative DESC, wr.id DESC) AS rn
                FROM watch_record wr
                WHERE wr.movie_id = :movieId
                  AND wr.rating IS NOT NULL
            ) t
            WHERE t.rn = 1
            """, nativeQuery = true)
    MovieRatingProjection findCinemoryRatingByMovieId(@Param("movieId") Long movieId);
}
