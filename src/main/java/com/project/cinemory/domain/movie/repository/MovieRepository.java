package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findByTmdbId(Long tmdbId);

    boolean existsByTmdbId(Long tmdbId);
}
