package com.project.cinemory.domain.boxoffice.repository;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BoxOfficeRecordRepository extends JpaRepository<BoxOfficeRecord, Long> {

    // 박스오피스 화면 조회 — movie(포스터)까지 1쿼리로 로딩
    @EntityGraph(attributePaths = "movie")
    List<BoxOfficeRecord> findByTargetDateAndRankTypeOrderByBoxOfficeRankAsc(LocalDate targetDate, RankType rankType);

    /**
     * 영화 상세의 "개봉 중" 판정과 누적 관객(service-layer-spec 4-2-B ②) - {@code from} 이후의 가장 최근 기록 1건.
     * 호출: (movieId, DAILY, 오늘(Asia/Seoul) - 7일). 없으면 개봉 중이 아니다.
     */
    Optional<BoxOfficeRecord> findFirstByMovieIdAndRankTypeAndTargetDateGreaterThanEqualOrderByTargetDateDesc(
            Long movieId, RankType rankType, LocalDate from);

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

    /**
     * 박스오피스 역방향 시드(tmdb-sync-spec 6-5) 대상 조회 — {@code DISTINCT}가 없으면 일별
     * 수집으로 같은 영화가 수십 행 쌓인 것을 그대로 순회해 TMDB 검색을 수십 번 중복 호출한다.
     *
     * <p>{@code ORDER BY openDate DESC NULLS LAST}로 최근 개봉작부터 확보한다 — 429로
     * 중단되더라도 사용자에게 더 중요한 최근작이 먼저 적재돼 있어야 한다.
     */
    @Query("""
        SELECT DISTINCT new com.project.cinemory.domain.boxoffice.repository.UnmatchedBoxOfficeTitle(
            b.movieTitleSnapshot, b.openDate, b.koficMovieCd)
        FROM BoxOfficeRecord b
        WHERE b.movie IS NULL
        ORDER BY b.openDate DESC NULLS LAST
        """)
    List<UnmatchedBoxOfficeTitle> findUnmatchedTitles(Pageable pageable);

    /**
     * 매일 자동 역방향 시드(cinemap-spec C-6-2)의 대상 — <b>그 집계일에 수집된</b> 미매칭 제목만. 전량이 아니라 날짜를 좁히는 이유는
     * 자동 실행이 과거 누적분(수동으로 다룰 몫)까지 매일 TMDB 한도를 쓰지 않게 하려는 것이다.
     */
    @Query("""
        SELECT DISTINCT new com.project.cinemory.domain.boxoffice.repository.UnmatchedBoxOfficeTitle(
            b.movieTitleSnapshot, b.openDate, b.koficMovieCd)
        FROM BoxOfficeRecord b
        WHERE b.movie IS NULL AND b.targetDate = :targetDate
        ORDER BY b.openDate DESC NULLS LAST
        """)
    List<UnmatchedBoxOfficeTitle> findUnmatchedTitlesOn(@Param("targetDate") LocalDate targetDate, Pageable pageable);

    /**
     * 역방향 시드가 tmdbId를 얻은 즉시 그 KOFIC 코드의 미연결 행 전체를 영화에 건다(release-build-spec §7 O-2) —
     * 제목 비교 없이 코드로 연결하므로 KOFIC·TMDB 제목이 달라도 붙는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BoxOfficeRecord b SET b.movie = :movie WHERE b.koficMovieCd = :koficMovieCd AND b.movie IS NULL")
    int linkUnlinkedByKoficMovieCd(@Param("koficMovieCd") String koficMovieCd,
                                   @Param("movie") com.project.cinemory.domain.movie.entity.Movie movie);

    // ---- 관리자 수동 연결(admin-spec 17) ----

    /**
     * 미연결 행을 KOFIC 코드로 묶는다(17-1). 제목·개봉일은 코드당 사실상 하나라 {@code MAX}로 모은다.
     * 정렬 고정: 최근 순위 진입일 내림차순, 같으면 코드.
     */
    @Query(value = """
        SELECT new com.project.cinemory.domain.boxoffice.repository.UnlinkedBoxOfficeGroup(
            b.koficMovieCd, MAX(b.movieTitleSnapshot), MAX(b.openDate),
            MIN(b.targetDate), MAX(b.targetDate), MIN(b.boxOfficeRank), COUNT(b))
        FROM BoxOfficeRecord b
        WHERE b.movie IS NULL
        GROUP BY b.koficMovieCd
        ORDER BY MAX(b.targetDate) DESC, b.koficMovieCd
        """,
            countQuery = "SELECT COUNT(DISTINCT b.koficMovieCd) FROM BoxOfficeRecord b WHERE b.movie IS NULL")
    org.springframework.data.domain.Page<UnlinkedBoxOfficeGroup> findUnlinkedGroups(Pageable pageable);

    long countByKoficMovieCdAndMovieIsNull(String koficMovieCd);

    /** 연결 해제(17-3) — 이 코드의 연결된 행 전부를 비운다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BoxOfficeRecord b SET b.movie = NULL WHERE b.koficMovieCd = :koficMovieCd AND b.movie IS NOT NULL")
    int unlinkByKoficMovieCd(@Param("koficMovieCd") String koficMovieCd);
}
