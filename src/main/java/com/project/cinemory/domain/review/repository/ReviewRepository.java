package com.project.cinemory.domain.review.repository;

import com.project.cinemory.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByUserIdAndMovieId(Long userId, Long movieId);

    // 영화 상세 화면의 리뷰 목록 — 작성자 정보 함께 로딩
    @EntityGraph(attributePaths = "user")
    Page<Review> findByMovieId(Long movieId, Pageable pageable);

    // 댓글 대상 검증용 — 엔티티 로딩 없이 소유자 id만 프로젝션 (존재 검증 겸용)
    @Query("SELECT r.user.id FROM Review r WHERE r.id = :reviewId")
    Optional<Long> findOwnerIdById(@Param("reviewId") Long reviewId);
}
