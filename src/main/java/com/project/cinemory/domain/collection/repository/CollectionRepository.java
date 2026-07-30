package com.project.cinemory.domain.collection.repository;

import com.project.cinemory.domain.collection.entity.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

    // "내 컬렉션" 목록 및 타인의 프로필에서 컬렉션 목록 조회에 공용으로 사용 (공개 조회)
    Page<Collection> findByUserId(Long userId, Pageable pageable);

    // 댓글 대상 검증용 — 엔티티 로딩 없이 소유자 id만 프로젝션 (존재 검증 겸용)
    @Query("SELECT c.user.id FROM Collection c WHERE c.id = :collectionId")
    Optional<Long> findOwnerIdById(@Param("collectionId") Long collectionId);
}
