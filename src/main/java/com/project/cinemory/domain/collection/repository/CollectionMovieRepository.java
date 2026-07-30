package com.project.cinemory.domain.collection.repository;

import com.project.cinemory.domain.collection.entity.CollectionMovie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CollectionMovieRepository extends JpaRepository<CollectionMovie, Long> {

    Optional<CollectionMovie> findByCollectionIdAndMovieId(Long collectionId, Long movieId);

    // 벌크 추가 시 이미 담겨있는 movieId를 걸러내기 위한 조회
    List<CollectionMovie> findByCollectionIdAndMovieIdIn(Long collectionId, List<Long> movieIds);

    // 컬렉션 상세(영화 목록) — movie는 @EntityGraph로 함께 로딩
    @EntityGraph(attributePaths = "movie")
    Page<CollectionMovie> findByCollectionId(Long collectionId, Pageable pageable);

    // 컬렉션 삭제 시 RESTRICT 대응 — 하위 행 명시적 정리
    void deleteAllByCollectionId(Long collectionId);

    // "내 컬렉션" 목록의 영화 개수 표시 — 컬렉션별 반복 쿼리 대신 벌크 그룹 카운트
    @Query("""
        SELECT cm.collection.id AS collectionId, COUNT(cm) AS count
        FROM CollectionMovie cm
        WHERE cm.collection.id IN :collectionIds
        GROUP BY cm.collection.id
        """)
    List<CollectionMovieCountProjection> countGroupByCollectionIdIn(@Param("collectionIds") List<Long> collectionIds);
}
