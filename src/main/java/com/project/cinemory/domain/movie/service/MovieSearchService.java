package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.dto.MovieSearchResponse;
import com.project.cinemory.domain.movie.dto.MovieSearchSuggestionResponse;
import com.project.cinemory.domain.movie.dto.MovieSummaryResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbSearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 영화 검색 — DB({@code registered}) + TMDB({@code suggestions}) 병합 (tmdb-sync-spec 6-8).
 *
 * <p>⚠️ {@code MovieQueryService}에 넣지 않는다. 그 클래스는 클래스 레벨
 * {@code @Transactional(readOnly = true)}라, 검색을 두면 <b>읽기 트랜잭션 안에서 TMDB HTTP
 * 호출</b>을 하게 된다 — 6-4에서 {@code MovieSyncService}를 non-transactional로 분리한 것과
 * 같은 문제다. 이 클래스는 쓰기가 없으므로 트랜잭션을 아예 붙이지 않고 리포지토리를 직접 호출한다.
 *
 * <p>{@code suggestions}는 {@code page == 1}에서만 채운다 — 결과를 넘겨보는 동안 TMDB를
 * 반복 호출할 이유가 없다("우리에게 없는 영화"는 첫 화면에서 한 번이면 충분).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSearchService {

    /** 다른 목록 엔드포인트({@code getMovieList})와 동일한 기본 페이지 크기. */
    private static final int PAGE_SIZE = 20;

    /**
     * {@code registered} 정렬 — 최신 개봉작 우선.
     *
     * <p>정렬을 명시하지 않으면 순서가 <b>정의되지 않는다.</b> 현재는 {@code title} 인덱스가 없어
     * 풀스캔 → 클러스터드 인덱스(PK) 순으로 읽히므로 사실상 적재 순인데, 지금 데이터가
     * {@code discover?sort_by=popularity.desc}로 들어와 <b>우연히 인기순처럼 보일 뿐</b>이다.
     * 박스오피스 역방향 시드와 온디맨드 {@code sync}가 섞이면 그 의미가 사라진다.
     *
     * <p>더 실질적인 이유는 <b>페이징 일관성</b>이다. {@code ORDER BY} 없는 {@code LIMIT/OFFSET}은
     * 페이지 간 순서를 보장하지 않아 같은 행이 중복되거나 누락될 수 있다.
     *
     * <p>⚠️ {@code id}를 tie-breaker로 함께 건다. 같은 날 개봉한 영화가 여럿이면
     * {@code releaseDate}만으로는 순서가 다시 불안정해져 위 문제가 그대로 재현된다.
     *
     * <p>⚠️ {@code NULLS LAST}를 명시하지 않는다. MySQL은 NULL을 가장 작은 값으로 취급하므로
     * {@code DESC}면 자동으로 마지막에 온다. 명시하면 Hibernate가 MySQL 미지원 문법을
     * {@code CASE WHEN ... IS NULL} 로 에뮬레이션해 쿼리만 지저분해진다.
     */
    private static final Sort SEARCH_SORT =
            Sort.by(Sort.Order.desc("releaseDate"), Sort.Order.desc("id"));

    private final MovieRepository movieRepository;
    private final TmdbClient tmdbClient;

    public MovieSearchResponse search(String query, Integer year, int page) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // TMDB는 1-based, Spring Pageable은 0-based다 (6-8 페이징 함정 #1).
        int resolvedPage = Math.max(page, 1);
        Pageable pageable = PageRequest.of(resolvedPage - 1, PAGE_SIZE, SEARCH_SORT);
        Page<MovieSummaryResponse> registered = movieRepository
                .findByTitleContainingOrOriginalTitleContaining(query, query, pageable)
                .map(MovieSummaryResponse::from);

        List<MovieSearchSuggestionResponse> suggestions =
                resolvedPage == 1 ? fetchSuggestions(query, year) : List.of();

        return new MovieSearchResponse(PageResponse.from(registered), suggestions);
    }

    /**
     * TMDB 장애는 구조가 폴백을 대신한다 — {@code registered}는 정상이므로 검색 자체는 죽지
     * 않고 {@code suggestions}만 빈 배열이 된다. 실패 유형을 구분하지 않는다(429·5xx·타임아웃·
     * 4xx 전부 동일 처리). 발동 시 {@code WARN}이 필수다 — 이 처리는 본질적으로 실패를 감추는
     * 장치라, 감춰진 실패를 볼 수단이 없으면 TMDB 토큰이 만료돼도 아무도 모른다.
     */
    private List<MovieSearchSuggestionResponse> fetchSuggestions(String query, Integer year) {
        try {
            List<TmdbSearchResponse.Item> results =
                    tmdbClient.searchMovieForSuggestions(query, year).resultsOrEmpty();
            if (results.isEmpty()) {
                return List.of();
            }

            List<Long> tmdbIds = results.stream().map(TmdbSearchResponse.Item::id).toList();
            Set<Long> registeredTmdbIds = new HashSet<>(movieRepository.findByTmdbIdIn(tmdbIds).stream()
                    .map(Movie::getTmdbId)
                    .toList());

            return results.stream()
                    .filter(item -> !registeredTmdbIds.contains(item.id()))
                    .map(MovieSearchSuggestionResponse::from)
                    .toList();

        } catch (RuntimeException e) {
            log.warn("영화 검색: TMDB suggestions 조회 실패, 빈 배열로 대체합니다. query={}, year={}", query, year, e);
            return List.of();
        }
    }
}
