package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieActor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovieActorRepository extends JpaRepository<MovieActor, Long> {

    @EntityGraph(attributePaths = "person")
    List<MovieActor> findByMovieIdOrderByRoleTierAsc(Long movieId);
}
