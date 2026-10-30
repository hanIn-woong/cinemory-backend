package com.project.cinemory.domain.user.repository;

import com.project.cinemory.domain.user.entity.UserFavoriteMovie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserFavoriteMovieRepository extends JpaRepository<UserFavoriteMovie, Long> {

    /** 프로필 응답용 — position 순, 영화까지 한 번에(N+1 방지). */
    @Query("select f from UserFavoriteMovie f join fetch f.movie where f.user.id = :userId order by f.position")
    List<UserFavoriteMovie> findByUserIdOrderByPosition(@Param("userId") Long userId);

    /**
     * 교체의 첫 단계 — 벌크 JPQL이라 <b>즉시 실행</b>된다. 엔티티 {@code delete()}로 바꾸면 Hibernate가 INSERT를 DELETE보다 먼저 flush해
     * {@code uk_user_favorite_position}·{@code uk_user_favorite_movie}를 위반한다. {@code clearAutomatically}를 켜지 않는다(4-6 사고) —
     * 호출 트랜잭션이 삭제 전에 이 엔티티를 로드하지 않으므로 지울 영속 객체가 없다.
     */
    @Modifying
    @Query("delete from UserFavoriteMovie f where f.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
