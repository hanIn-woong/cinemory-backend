package com.project.cinemory.domain.keyword.repository;

import com.project.cinemory.domain.keyword.entity.Keyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KeywordRepository extends JpaRepository<Keyword, Long> {

    /**
     * {@code MovieSyncPersister}의 키워드 upsert용 (tmdb-sync-spec 6-11). 영화 한 편의 키워드 전체를
     * 1쿼리로 읽어 신규/기존을 가른다 — 건별 조회는 영화당 약 10회 왕복이다.
     */
    List<Keyword> findByTmdbKeywordIdIn(Collection<Long> tmdbKeywordIds);
}
