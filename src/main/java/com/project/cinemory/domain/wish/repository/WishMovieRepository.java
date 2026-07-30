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

    // "내 위시리스트" — 최근 추가순, movie는 @EntityGraph로 함께 로딩
    @EntityGraph(attributePaths = "movie")
    Page<WishMovie> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
