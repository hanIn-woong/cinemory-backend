package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.dto.MovieSearchKeyRebuildResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 검색 키 일괄 재생성 (service-layer-spec 4-2-C ①). V49 직후 기존 행을 채우는 일회성 관리자 작업이다 - SQL로 채우지 않는 이유는
 * 규칙을 {@code SearchKey} 한 곳에 두기 위해서다. id 커서로 {@link #CHUNK_SIZE}건씩 읽고 청크마다 트랜잭션을 나눠
 * 긴 트랜잭션·큰 영속성 컨텍스트를 피한다. 멱등 - 여러 번 돌려도 이미 맞는 행은 UPDATE가 나가지 않는다.
 */
@Slf4j
@Service
public class MovieSearchKeyService {

    static final int CHUNK_SIZE = 1_000;

    private final MovieRepository movieRepository;
    private final TransactionTemplate transactionTemplate;

    public MovieSearchKeyService(MovieRepository movieRepository, PlatformTransactionManager transactionManager) {
        this.movieRepository = movieRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public MovieSearchKeyRebuildResponse rebuildAll() {
        long fromId = 0L;
        int processed = 0;
        int updated = 0;
        while (true) {
            final long cursor = fromId;
            Chunk chunk = transactionTemplate.execute(status -> rebuildChunk(cursor));
            if (chunk == null || chunk.size() == 0) {
                break;
            }
            processed += chunk.size();
            updated += chunk.updated();
            fromId = chunk.lastId();
            log.info("영화 검색 키 재생성 진행: processed={}, updated={}, lastId={}", processed, updated, fromId);
            if (chunk.size() < CHUNK_SIZE) {
                break;
            }
        }
        log.info("영화 검색 키 재생성 완료: processed={}, updated={}", processed, updated);
        return new MovieSearchKeyRebuildResponse(processed, updated);
    }

    private Chunk rebuildChunk(long fromId) {
        List<Movie> movies = movieRepository.findByIdGreaterThanOrderByIdAsc(fromId, PageRequest.of(0, CHUNK_SIZE));
        int updated = 0;
        for (Movie movie : movies) {
            String before = movie.getSearchKey();
            movie.refreshSearchKey();
            if (!movie.getSearchKey().equals(before)) {
                updated++;
            }
        }
        Long lastId = movies.isEmpty() ? fromId : movies.get(movies.size() - 1).getId();
        return new Chunk(movies.size(), updated, lastId);
    }

    private record Chunk(int size, int updated, long lastId) {
    }
}
