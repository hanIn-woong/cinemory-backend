package com.project.cinemory.domain.comment.repository;

import com.project.cinemory.domain.comment.entity.Comment;
import com.project.cinemory.domain.comment.entity.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 댓글 목록 — 작성자(User)를 함께 로딩. idx_comment_target(target_type, target_id) 적중.
     * 정렬 기준을 created_at이 아닌 id로 두는 이유는 동일 시각 생성 시 순서가 불안정해지는 것을
     * 막기 위함이며(대표 리뷰 판정 기준과 동일한 근거), 추후 커서 페이징 전환에도 그대로 쓸 수 있다.
     */
    @EntityGraph(attributePaths = "user")
    Page<Comment> findByTargetTypeAndTargetIdOrderByIdAsc(TargetType targetType, Long targetId, Pageable pageable);

    long countByTargetTypeAndTargetId(TargetType targetType, Long targetId);

    // 목록 화면의 댓글 수 배지 — 대상별 반복 쿼리 대신 벌크 그룹 카운트
    @Query("""
        SELECT c.targetId AS targetId, COUNT(c) AS count
        FROM Comment c
        WHERE c.targetType = :targetType AND c.targetId IN :targetIds
        GROUP BY c.targetId
        """)
    List<CommentCountProjection> countGroupByTargetIdIn(@Param("targetType") TargetType targetType,
                                                        @Param("targetIds") Collection<Long> targetIds);

    /**
     * 대상(Collection/Review) 삭제 시 고아 댓글 정리.
     * comment.target_id는 다형 참조라 FK가 없어 DB가 자동 정리해주지 않는다.
     *
     * <p><b>clearAutomatically를 켜지 않는 이유</b>: 이 메서드는 삭제 트랜잭션 도중
     * (예: CollectionService.deleteCollection) 호출된다. 영속성 컨텍스트를 clear하면
     * 앞서 수행한 deleteAllByCollectionId의 <i>아직 flush되지 않은 remove</i>가 함께 폐기되어
     * collection_movie 행이 남고, 이어지는 collection 삭제가 FK RESTRICT에 걸린다.
     * 호출 지점에서 Comment 엔티티를 로딩해 들고 있지 않으므로 stale 위험도 없다.
     */
    @Modifying
    @Query("DELETE FROM Comment c WHERE c.targetType = :targetType AND c.targetId = :targetId")
    int deleteByTarget(@Param("targetType") TargetType targetType, @Param("targetId") Long targetId);
}
