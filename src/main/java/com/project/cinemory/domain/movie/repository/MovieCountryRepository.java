package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieCountry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovieCountryRepository extends JpaRepository<MovieCountry, Long> {

    @EntityGraph(attributePaths = "country")
    List<MovieCountry> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "country")
    List<MovieCountry> findByMovieIdIn(List<Long> movieIds);
}
