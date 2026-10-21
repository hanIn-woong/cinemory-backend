package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieKeywordRepository extends JpaRepository<MovieKeyword, Long> {

    /** 재동기화 "전량 삭제 후 재삽입"의 삭제 단계 (tmdb-sync-spec 6-4·6-11). 벌크 DML — 파생 delete 아님. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieKeyword mk where mk.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);
}
