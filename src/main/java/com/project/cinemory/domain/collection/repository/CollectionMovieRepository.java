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

    // 컬렉션 상세(영화 목록) — movie는 @EntityGraph로 함께 로딩. id DESC는 position 동률 대비 보조키(5-4-A ①)
    @EntityGraph(attributePaths = "movie")
    Page<CollectionMovie> findByCollectionIdOrderByPositionAscIdDesc(Long collectionId, Pageable pageable);

    // 순서 재작성용 — 컬렉션에 담긴 영화 전량 (5-4-A ② 상한 500)
    List<CollectionMovie> findByCollectionId(Long collectionId);

    // 영화를 맨 위에 넣기 위한 기준값 — 비어 있으면 1을 돌려 첫 행이 0이 되게 한다(4-5-A)
    @Query("SELECT COALESCE(MIN(cm.position), 1) FROM CollectionMovie cm WHERE cm.collection.id = :collectionId")
    int findMinPositionByCollectionId(@Param("collectionId") Long collectionId);

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

    /**
     * 컬렉션 카드 미리보기 포스터 — 컬렉션마다 사용자가 앞에 배치한 순서(position ASC)로 최대 {@code limit}장.
     * JPQL은 윈도 함수를 지원하지 않아 native query다(4-5-A). 포스터 없는 영화는 서버에서 걸러야
     * 클라이언트가 받은 뒤 칸이 줄지 않는다. 호출자는 {@code collectionIds}가 비지 않았음을 보장해야 한다.
     */
    @Query(value = """
        SELECT t.collection_id AS collectionId, t.poster_path AS posterPath
        FROM (
            SELECT cm.collection_id, m.poster_path,
                   ROW_NUMBER() OVER (PARTITION BY cm.collection_id ORDER BY cm.position ASC, cm.id DESC) AS rn
            FROM collection_movie cm
            JOIN movie m ON m.id = cm.movie_id
            WHERE cm.collection_id IN (:collectionIds)
              AND m.poster_path IS NOT NULL
        ) t
        WHERE t.rn <= :limit
        ORDER BY t.collection_id, t.rn
        """, nativeQuery = true)
    List<CollectionPreviewPosterProjection> findPreviewPostersByCollectionIdIn(
            @Param("collectionIds") List<Long> collectionIds, @Param("limit") int limit);
}
