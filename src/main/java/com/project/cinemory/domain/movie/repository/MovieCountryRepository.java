package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieCountry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MovieCountryRepository extends JpaRepository<MovieCountry, Long> {

    @EntityGraph(attributePaths = "country")
    List<MovieCountry> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "country")
    List<MovieCountry> findByMovieIdIn(List<Long> movieIds);

    /** 재동기화 "전량 삭제 후 재삽입"의 삭제 단계 (tmdb-sync-spec 6-4). 벌크 DML — 파생 delete 아님. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieCountry mc where mc.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);
}
