package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieDirector;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovieDirectorRepository extends JpaRepository<MovieDirector, Long> {

    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieId(Long movieId);
}
