package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieGenre;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovieGenreRepository extends JpaRepository<MovieGenre, Long> {

    @EntityGraph(attributePaths = "genre")
    List<MovieGenre> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "genre")
    List<MovieGenre> findByMovieIdIn(List<Long> movieIds);
}
