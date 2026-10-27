package com.project.cinemory.domain.theater.repository;

import com.project.cinemory.domain.theater.entity.Theater;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    /**
     * 반경 검색 1차 필터 — Bounding Box.
     *
     * <p>{@code idx_theater_lat_lng (latitude, longitude)}를 사용한다. 다만 MySQL 복합 인덱스는
     * <b>첫 range 조건 이후 컬럼을 탐색 키로 쓰지 못하므로</b> latitude로만 범위 탐색이 일어나고
     * longitude는 Index Condition Pushdown 필터로 동작한다. 전국 상영관이 수백 개 규모라
     * 이 정도로 충분하며, 데이터가 크게 늘면 POINT 컬럼 + SPATIAL 인덱스로 전환한다.
     */
    @Query("""
        SELECT t FROM Theater t
        WHERE t.latitude BETWEEN :minLatitude AND :maxLatitude
          AND t.longitude BETWEEN :minLongitude AND :maxLongitude
          AND t.closedAt IS NULL
        """)
    List<Theater> findWithinBoundingBox(@Param("minLatitude") BigDecimal minLatitude,
                                        @Param("maxLatitude") BigDecimal maxLatitude,
                                        @Param("minLongitude") BigDecimal minLongitude,
                                        @Param("maxLongitude") BigDecimal maxLongitude);

    // 동기화에서 "이번에 사라진 극장"을 가릴 때 쓰는 영업 중 전체(C-5-1) — 극장 약 700곳이라 전량 조회가 부담이 없다
    List<Theater> findByClosedAtIsNull();

    /**
     * 관리자 극장 목록(C-8-2) — 숨긴 극장도 포함한다. 선택 필터는 {@code null} 대신 모드 값(0=전체)으로 받는다:
     * Boolean/nullable 파라미터를 {@code :p is null}로 비교하면 JPQL 타입 추론이 DB마다 달라 위험하다.
     *
     * @param bookingMode 0 전체 · 1 예매 링크 있음 · 2 없음
     * @param closedMode  0 전체 · 1 숨긴 극장만 · 2 영업 중만
     */
    @Query("""
        SELECT t FROM Theater t
        WHERE (t.name LIKE :pattern ESCAPE '!' OR t.address LIKE :pattern ESCAPE '!')
          AND (:chain IS NULL OR t.chainName = :chain)
          AND (:bookingMode = 0 OR (:bookingMode = 1 AND t.bookingUrl IS NOT NULL) OR (:bookingMode = 2 AND t.bookingUrl IS NULL))
          AND (:closedMode = 0 OR (:closedMode = 1 AND t.closedAt IS NOT NULL) OR (:closedMode = 2 AND t.closedAt IS NULL))
        """)
    Page<Theater> searchForAdmin(@Param("pattern") String pattern, @Param("chain") String chain,
                                 @Param("bookingMode") int bookingMode, @Param("closedMode") int closedMode,
                                 Pageable pageable);

    // 시드 upsert 시 기존 극장을 한 번에 읽는다(C-4-5)
    List<Theater> findBySourceCodeIn(Collection<String> sourceCodes);
}
