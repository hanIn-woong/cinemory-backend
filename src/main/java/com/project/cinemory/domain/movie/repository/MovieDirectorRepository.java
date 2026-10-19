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

    /** 관리자 인물 목록의 연출 건수(admin-spec 12-3) — 페이지의 인물 ID로 묶어 1쿼리. 연출 기록이 없는 인물은 행이 없다. */
    @Query("select md.person.id as personId, count(md) as count from MovieDirector md where md.person.id in :personIds group by md.person.id")
    List<com.project.cinemory.domain.person.repository.PersonCreditCountProjection> countGroupByPersonIdIn(
            @Param("personIds") java.util.Collection<Long> personIds);

    /** 재동기화 "전량 삭제 후 재삽입"의 삭제 단계 (tmdb-sync-spec 6-4). 벌크 DML — 파생 delete 아님. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieDirector md where md.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);
}
