package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieDirector;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MovieDirectorRepository extends JpaRepository<MovieDirector, Long> {

    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieId(Long movieId);

    // 4-5(Collection 상세 목록)에서 필요해져 추가된 벌크 조회
    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieIdIn(List<Long> movieIds);

    /** 재동기화 "전량 삭제 후 재삽입"의 삭제 단계 (tmdb-sync-spec 6-4). 벌크 DML — 파생 delete 아님. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieDirector md where md.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);
}
