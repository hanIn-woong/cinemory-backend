package com.project.cinemory.domain.collection.repository;

import com.project.cinemory.domain.collection.entity.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

    // "내 컬렉션" 목록 및 타인의 프로필에서 컬렉션 목록 조회에 공용으로 사용 (공개 조회)
    // position은 UNIQUE가 아니라(동시 생성 시 MIN-1이 겹칠 수 있다) id DESC 보조키로 전순서를 만든다(5-4-A ①).
    Page<Collection> findByUserIdOrderByPositionAscIdDesc(Long userId, Pageable pageable);

    // 순서 재작성용 — 사용자의 컬렉션 전량 (5-4-A ② 상한 200)
    List<Collection> findByUserId(Long userId);

    // 새 컬렉션을 맨 위에 넣기 위한 기준값 — 컬렉션이 없으면 1을 돌려 첫 행이 0이 되게 한다(4-5-A)
    @Query("SELECT COALESCE(MIN(c.position), 1) FROM Collection c WHERE c.user.id = :userId")
    int findMinPositionByUserId(@Param("userId") Long userId);

    // 댓글 대상 검증용 — 엔티티 로딩 없이 소유자 id만 프로젝션 (존재 검증 겸용)
    @Query("SELECT c.user.id FROM Collection c WHERE c.id = :collectionId")
    Optional<Long> findOwnerIdById(@Param("collectionId") Long collectionId);
}
