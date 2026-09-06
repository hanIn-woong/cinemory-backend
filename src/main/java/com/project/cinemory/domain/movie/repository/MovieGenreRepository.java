package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieGenre;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MovieGenreRepository extends JpaRepository<MovieGenre, Long> {

    @EntityGraph(attributePaths = "genre")
    List<MovieGenre> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "genre")
    List<MovieGenre> findByMovieIdIn(List<Long> movieIds);

    /** 재동기화 "전량 삭제 후 재삽입"의 삭제 단계 (tmdb-sync-spec 6-4). 벌크 DML — 파생 delete 아님. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieGenre mg where mg.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);
}
