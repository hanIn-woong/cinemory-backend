package com.project.cinemory.domain.follow.repository;

import com.project.cinemory.domain.follow.entity.Follow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);

    // uk_follow 유니크 인덱스로 최대 1행만 적중하므로 파생 삭제 쿼리로 충분
    long deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);

    // 팔로잉(내가 따르는) 수
    long countByFollowerId(Long followerId);

    // 팔로워(나를 따르는) 수
    long countByFollowingId(Long followingId);

    // 팔로워 목록 — follower(User)를 함께 로딩
    @EntityGraph(attributePaths = "follower")
    Page<Follow> findByFollowingId(Long followingId, Pageable pageable);

    // 팔로잉 목록 — following(User)을 함께 로딩
    @EntityGraph(attributePaths = "following")
    Page<Follow> findByFollowerId(Long followerId, Pageable pageable);

    // 상호 팔로우(맞팔) 단건 판정 — 반환값이 2면 맞팔
    @Query("""
        SELECT COUNT(f) FROM Follow f
        WHERE (f.follower.id = :userA AND f.following.id = :userB)
           OR (f.follower.id = :userB AND f.following.id = :userA)
        """)
    long countMutual(@Param("userA") Long userA, @Param("userB") Long userB);

    // 벌크: viewer가 팔로우 중인 대상 id 집합
    @Query("""
        SELECT f.following.id FROM Follow f
        WHERE f.follower.id = :viewerId AND f.following.id IN :targetIds
        """)
    List<Long> findFollowingIdsIn(@Param("viewerId") Long viewerId,
                                  @Param("targetIds") Collection<Long> targetIds);

    // 벌크: viewer를 팔로우 중인 대상 id 집합
    @Query("""
        SELECT f.follower.id FROM Follow f
        WHERE f.following.id = :viewerId AND f.follower.id IN :targetIds
        """)
    List<Long> findFollowerIdsIn(@Param("viewerId") Long viewerId,
                                 @Param("targetIds") Collection<Long> targetIds);
}
