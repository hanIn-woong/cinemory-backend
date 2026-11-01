package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieCompany;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MovieCompanyRepository extends JpaRepository<MovieCompany, Long> {

    // 영화 상세(4-2-B) - 제작사를 함께 로딩. 3개로 자르는 것은 서비스에서(영화당 몇 개라 Pageable 불필요)
    @EntityGraph(attributePaths = "company")
    List<MovieCompany> findByMovieIdOrderByDisplayOrderAsc(Long movieId);

    /** 재동기화 "전량 삭제 후 재삽입"의 삭제 단계(6-12-3). 벌크 DML - 파생 delete 아님. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieCompany mc where mc.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);
}
