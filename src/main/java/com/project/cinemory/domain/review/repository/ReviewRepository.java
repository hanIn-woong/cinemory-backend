package com.project.cinemory.domain.review.repository;

import com.project.cinemory.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByUserIdAndMovieId(Long userId, Long movieId);

    // 영화 상세 화면의 리뷰 목록 — 작성자 정보 함께 로딩
    @EntityGraph(attributePaths = "user")
    Page<Review> findByMovieId(Long movieId, Pageable pageable);

    // 댓글 대상 검증용 — 엔티티 로딩 없이 소유자 id만 프로젝션 (존재 검증 겸용)
    @Query("SELECT r.user.id FROM Review r WHERE r.id = :reviewId")
    Optional<Long> findOwnerIdById(@Param("reviewId") Long reviewId);

    /**
     * 표시용 별점의 2단계 폴백 — {@code review.rating}은 v15에서 제거되어 별점은
     * {@code watch_record} 단일 출처의 파생값이다.
     * 대표 기록(is_representative = true)의 rating → null이면 rating IS NOT NULL인
     * 가장 최근(id DESC) 기록의 rating → 그것도 없으면 null.
     *
     * <p>{@code Review}를 기준으로 {@code WatchRecord}에 두 번 LEFT JOIN하므로 시청 기록이
     * 아예 없는 리뷰도 한 행으로 포함되고(3rd 폴백), 페이지 단위로 한 번만 호출하면 되어
     * 리뷰 건수만큼 반복 조회하는 N+1을 피한다.
     */
    @Query("""
            SELECT r.id AS reviewId, COALESCE(rep.rating, fallback.rating) AS rating
            FROM Review r
            LEFT JOIN WatchRecord rep
                ON rep.user = r.user AND rep.movie = r.movie AND rep.representative = true
            LEFT JOIN WatchRecord fallback
                ON fallback.id = (
                    SELECT MAX(wr.id) FROM WatchRecord wr
                    WHERE wr.user = r.user AND wr.movie = r.movie AND wr.rating IS NOT NULL
                )
            WHERE r.id IN :reviewIds
            """)
    List<ReviewRatingProjection> findResolvedRatingsByReviewIds(@Param("reviewIds") Collection<Long> reviewIds);
}
