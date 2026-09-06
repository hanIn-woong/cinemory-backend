package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@code tmdbId} 하나를 우리 DB에 반영하는 유일한 진입점 (tmdb-sync-spec 6-4).
 *
 * <p>⚠️ <b>트랜잭션이 없다.</b> TMDB 왕복을 DB 트랜잭션 밖에 둬야 커넥션을 쥔 채
 * 네트워크를 기다리지 않는다 — 시드로 수백 편을 도는 순간 커넥션 풀이 마른다.
 * DB 반영은 {@link MovieSyncPersister}(별도 빈, {@code @Transactional})가 전담한다.
 *
 * <p>같은 클래스 안에서 fetch/persist로 나누지 않고 빈을 분리한 이유 — 자기호출이면
 * 프록시를 타지 않아 {@code @Transactional}이 조용히 무시된다.
 *
 * <p><b>멱등하지 않다.</b> 항상 TMDB를 호출해 최신 상태로 덮어쓴다. "이미 있으면
 * 건너뛴다"는 시드 서비스({@code MovieSeedService})의 사전 필터 책임이다.
 */
@Service
@RequiredArgsConstructor
public class MovieSyncService {

    private final TmdbClient tmdbClient;
    private final MovieSyncPersister persister;

    public Movie syncFromTmdb(Long tmdbId) {
        TmdbMovieDetailResponse detail = tmdbClient.fetchMovieDetail(tmdbId);
        return persister.persist(detail);
    }
}
