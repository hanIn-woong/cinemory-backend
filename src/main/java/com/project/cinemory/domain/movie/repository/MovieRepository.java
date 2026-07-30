package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findByTmdbId(Long tmdbId);

    boolean existsByTmdbId(Long tmdbId);

    // 박스오피스 수집 배치의 1순위 매칭 — kofic_movie_cd 직접 매칭 (벌크 1쿼리)
    List<Movie> findByKoficMovieCdIn(Collection<String> koficMovieCds);

    // 재매칭 배치의 2순위 매칭 — 제목 완전 일치 (동명이인 판별은 서비스에서 처리)
    List<Movie> findByTitle(String title);
}
