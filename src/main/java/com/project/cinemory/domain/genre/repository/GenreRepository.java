package com.project.cinemory.domain.genre.repository;

import com.project.cinemory.domain.genre.entity.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GenreRepository extends JpaRepository<Genre, Long> {

    /**
     * 시드 적재의 멱등 upsert용. 응답에 담긴 ID 전체를 1쿼리로 읽어
     * 신규/기존을 가른다 (건별 조회는 장르 수만큼 쿼리가 나간다).
     */
    List<Genre> findByTmdbGenreIdIn(Collection<Integer> tmdbGenreIds);
}
