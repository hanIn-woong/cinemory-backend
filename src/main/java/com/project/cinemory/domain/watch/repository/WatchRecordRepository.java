package com.project.cinemory.domain.watch.repository;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WatchRecordRepository extends JpaRepository<WatchRecord, Long> {

    Optional<WatchRecord> findByUserIdAndMovieIdAndRepresentativeTrue(Long userId, Long movieId);

    // 내보내기(settings-spec ST-5) — 사용자 한 명의 전체 기록을 한 번에. movie·ottPlatform은 함께 로딩(N+1 회피).
    @EntityGraph(attributePaths = {"movie", "ottPlatform"})
    List<WatchRecord> findAllByUserId(Long userId);

    // 관리자 회원·영화 상세의 카운트 (admin-spec 3-3, 3-5)
    long countByUserId(Long userId);

    long countByUserIdAndRepresentativeTrue(Long userId);

    long countByMovieId(Long movieId);

    @Query("SELECT MAX(wr.watchDate) FROM WatchRecord wr WHERE wr.user.id = :userId")
    LocalDate findLastWatchDateByUserId(@Param("userId") Long userId);

    // 특정 영화의 전체 시청 기록(회차별) 조회 — getWatchLog
    List<WatchRecord> findByUserIdAndMovieIdOrderByIdDesc(Long userId, Long movieId);

    // 대표 삭제 후 재선정 대상 — 가장 오래된 기록(2026-10-17, "처음 기록이 대표" 정책)
    Optional<WatchRecord> findFirstByUserIdAndMovieIdOrderByIdAsc(Long userId, Long movieId);

    // "내 영화" 목록 — 대표 기록만, movie는 @EntityGraph로 함께 로딩(N+1 회피).
    // 정렬은 메서드명에 고정하지 않고 Service가 RecordSort로 만든 Pageable에 싣는다.
    @EntityGraph(attributePaths = "movie")
    Page<WatchRecord> findByUserIdAndRepresentativeTrue(Long userId, Pageable pageable);

    // 별점 필터 (5B.1) — 위 메서드에 WHERE만 더한다. rating은 decimal(3,1)/BigDecimal이라 경계도 BigDecimal.
    // Between은 양끝 포함, NULL은 자연히 빠진다. 별점 없음은 별도 메서드.
    @EntityGraph(attributePaths = "movie")
    Page<WatchRecord> findByUserIdAndRepresentativeTrueAndRatingBetween(Long userId, BigDecimal min, BigDecimal max, Pageable pageable);

    @EntityGraph(attributePaths = "movie")
    Page<WatchRecord> findByUserIdAndRepresentativeTrueAndRatingIsNull(Long userId, Pageable pageable);

    // 관람일순 (2026-10-18) — 대표 기록 행을 영화별 MAX(최근 관람일순)/MIN(오래된 관람일순)(watchDate)로 정렬한다. 대표가 "처음 기록"이 되면서
    // 재관람 날짜가 대표 행에 없기 때문이다. MAX·MIN은 NULL을 무시하므로 어떤 기록의 날짜가 비어도 나머지 날짜로 정렬되고, 모든 기록의 날짜가
    // 비어야 뒤로 간다. 정렬을 메서드에 박았으므로 호출하는 Pageable은 정렬이 없어야 한다(Service가 RecordSort를 쓰지 않고 PageRequest.of(page, size)로 만든다).
    // 별점 필터는 파라미터로 합쳤다 — 필터 없음은 min/max null·unrated false, 미평가는 unrated true, 범위는 min/max.
    // ⚠️ countQuery를 따로 둔다 — JOIN FETCH 쿼리에서 Spring Data가 count를 파생하면 실패한다.
    String REPRESENTATIVE_FILTER = """
            wr.user.id = :userId AND wr.representative = TRUE
            AND (:min IS NULL OR wr.rating >= :min) AND (:max IS NULL OR wr.rating <= :max)
            AND (:unrated = FALSE OR wr.rating IS NULL)
            """;

    @Query(value = "SELECT wr FROM WatchRecord wr JOIN FETCH wr.movie WHERE " + REPRESENTATIVE_FILTER + """
            ORDER BY (SELECT MAX(l.watchDate) FROM WatchRecord l WHERE l.user.id = wr.user.id AND l.movie.id = wr.movie.id) DESC NULLS LAST,
                     wr.id DESC
            """,
            countQuery = "SELECT COUNT(wr) FROM WatchRecord wr WHERE " + REPRESENTATIVE_FILTER)
    Page<WatchRecord> findRepresentativesOrderByLatestWatchDate(
            @Param("userId") Long userId, @Param("min") BigDecimal min, @Param("max") BigDecimal max,
            @Param("unrated") boolean unrated, Pageable pageable);

    @Query(value = "SELECT wr FROM WatchRecord wr JOIN FETCH wr.movie WHERE " + REPRESENTATIVE_FILTER + """
            ORDER BY (SELECT MIN(l.watchDate) FROM WatchRecord l WHERE l.user.id = wr.user.id AND l.movie.id = wr.movie.id) ASC NULLS LAST,
                     wr.id DESC
            """,
            countQuery = "SELECT COUNT(wr) FROM WatchRecord wr WHERE " + REPRESENTATIVE_FILTER)
    Page<WatchRecord> findRepresentativesOrderByEarliestWatchDate(
            @Param("userId") Long userId, @Param("min") BigDecimal min, @Param("max") BigDecimal max,
            @Param("unrated") boolean unrated, Pageable pageable);

    // 목록 한 페이지의 영화들에 대한 전체 회차 — 카드에 보일 "최근 기록"(날짜·별점)을 고르는 데 쓴다. 페이지 크기(최대 수십 편)로 한정된다.
    List<WatchRecord> findByUserIdAndMovieIdIn(Long userId, java.util.Collection<Long> movieIds);

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
