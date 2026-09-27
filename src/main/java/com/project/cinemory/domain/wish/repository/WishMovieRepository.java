package com.project.cinemory.domain.wish.repository;

import com.project.cinemory.domain.wish.entity.WishMovie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WishMovieRepository extends JpaRepository<WishMovie, Long> {

    Optional<WishMovie> findByUserIdAndMovieId(Long userId, Long movieId);

    boolean existsByUserIdAndMovieId(Long userId, Long movieId);

    // "내 위시리스트" — movie는 @EntityGraph로 함께 로딩. 정렬은 Service가 WishSort로 만든
    // Pageable에 싣는다(메서드명에 OrderBy를 두면 Pageable의 Sort와 겹쳐 붙는다).
    @EntityGraph(attributePaths = "movie")
    Page<WishMovie> findByUserId(Long userId, Pageable pageable);
}
