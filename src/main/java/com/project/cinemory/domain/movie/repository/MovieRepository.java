package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findByTmdbId(Long tmdbId);

    boolean existsByTmdbId(Long tmdbId);

    // 박스오피스 수집 배치의 1순위 매칭 — kofic_movie_cd 직접 매칭 (벌크 1쿼리)
    List<Movie> findByKoficMovieCdIn(Collection<String> koficMovieCds);

    // 재매칭 배치의 2순위 매칭 — 제목 완전 일치 (동명이인 판별은 서비스에서 처리)
    List<Movie> findByTitle(String title);

    // 영화 검색의 registered 섹션 (6-8, v13으로 6-9에서 originalTitle 추가) — LIKE '%q%' 풀스캔이다.
    // movie.title/original_title에 인덱스가 없고 선행 와일드카드는 B-tree를 원리적으로 못 타
    // 인덱스를 추가해도 무의미하다. 현재 규모(수십~수천 행)에선 무해하며, FULLTEXT+ngram 전환은 잔여 #20.
    //
    // original_title도 함께 보는 이유(6-9) — movie.title은 ko-KR 제목만 담아 "avatar" 같은
    // 원어 검색어가 "아바타"에 안 걸린다. 두 컬럼을 OR로 묶어 두 검색어 체계를 다 받는다.
    Page<Movie> findByTitleContainingOrOriginalTitleContaining(String title, String originalTitle, Pageable pageable);

    // 영화 검색의 suggestions 중복 제거 (6-8) — TMDB 결과 중 이미 등록된 tmdbId를 걸러낸다.
    List<Movie> findByTmdbIdIn(Collection<Long> tmdbIds);

    // resync의 id 커서 (6-9) — 조건 없이 전체를 돈다. updated_at 기준을 기각한 이유는
    // MovieSeedService.resync 문서 참고 (dirty check가 무변경 시 UPDATE를 안 내 같은 행을 계속 잡는다).
    List<Movie> findByIdGreaterThanOrderByIdAsc(Long fromId, Pageable pageable);
}
