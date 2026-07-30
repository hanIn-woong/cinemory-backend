package com.project.cinemory.domain.theater.repository;

import com.project.cinemory.domain.theater.entity.Theater;
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
        """)
    List<Theater> findWithinBoundingBox(@Param("minLatitude") BigDecimal minLatitude,
                                        @Param("maxLatitude") BigDecimal maxLatitude,
                                        @Param("minLongitude") BigDecimal minLongitude,
                                        @Param("maxLongitude") BigDecimal maxLongitude);

    // 시드 적재 시 이미 존재하는 극장을 걸러내기 위한 벌크 조회
    @Query("SELECT t.sourceCode FROM Theater t WHERE t.sourceCode IN :sourceCodes")
    List<String> findSourceCodesIn(@Param("sourceCodes") Collection<String> sourceCodes);
}
