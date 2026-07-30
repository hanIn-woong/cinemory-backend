package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieDirector;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovieDirectorRepository extends JpaRepository<MovieDirector, Long> {

    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieId(Long movieId);

    // 4-5(Collection 상세 목록)에서 필요해져 추가된 벌크 조회
    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieIdIn(List<Long> movieIds);
}
