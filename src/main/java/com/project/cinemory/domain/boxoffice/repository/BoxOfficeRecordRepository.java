package com.project.cinemory.domain.boxoffice.repository;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BoxOfficeRecordRepository extends JpaRepository<BoxOfficeRecord, Long> {

    // 박스오피스 화면 조회 — movie(포스터)까지 1쿼리로 로딩
    @EntityGraph(attributePaths = "movie")
    List<BoxOfficeRecord> findByTargetDateAndRankTypeOrderByBoxOfficeRankAsc(LocalDate targetDate, RankType rankType);

    // 조회 시 날짜를 지정하지 않으면 최신 집계일을 사용한다
    @Query("SELECT MAX(b.targetDate) FROM BoxOfficeRecord b WHERE b.rankType = :rankType")
    Optional<LocalDate> findLatestTargetDate(@Param("rankType") RankType rankType);

    /**
     * 멱등 수집용 — 해당 집계일/구분에 이미 적재된 KOFIC 영화 코드 집합.
     * uk_box_office_record(target_date, rank_type, kofic_movie_cd) 위반을 사전에 피한다.
     */
    @Query("""
        SELECT b.koficMovieCd FROM BoxOfficeRecord b
        WHERE b.targetDate = :targetDate AND b.rankType = :rankType
        """)
    List<String> findKoficMovieCds(@Param("targetDate") LocalDate targetDate,
                                   @Param("rankType") RankType rankType);

    // 재매칭 배치 대상 — TMDB 매칭에 실패해 movie_id가 NULL로 남은 행
    List<BoxOfficeRecord> findByMovieIsNull(Pageable pageable);
}
