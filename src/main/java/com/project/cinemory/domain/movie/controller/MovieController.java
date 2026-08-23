package com.project.cinemory.domain.movie.controller;

import com.project.cinemory.domain.movie.dto.ActorResponse;
import com.project.cinemory.domain.movie.dto.MovieDetailResponse;
import com.project.cinemory.domain.movie.dto.MovieListItemResponse;
import com.project.cinemory.domain.movie.dto.MovieSearchResponse;
import com.project.cinemory.domain.movie.service.MovieQueryService;
import com.project.cinemory.domain.movie.service.MovieSearchService;
import com.project.cinemory.global.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영화 공개 조회 전용(5-2). {@code viewerId}를 받지 않는다 — 영화는 사용자 소유 데이터가 아니라
 * {@code UserAccessPolicy} 적용 대상이 아니다(4-7의 Theater/BoxOffice와 동일한 성격).
 *
 * <p>{@code /search}는 {@code getMovieList}와 별도 빈({@code MovieSearchService})이 처리한다
 * (tmdb-sync-spec 6-8) — DB뿐 아니라 TMDB도 함께 부르는 병합 검색이라 응답 계약이 다르다.
 */
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieQueryService movieQueryService;
    private final MovieSearchService movieSearchService;

    @Operation(summary = "영화 목록 조회")
    @GetMapping
    public ResponseEntity<PageResponse<MovieListItemResponse>> getMovieList(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(movieQueryService.getMovieList(pageable)));
    }

    /**
     * DB({@code registered}) + TMDB({@code suggestions}) 병합 검색 (6-8). 경로 리터럴이라
     * {@code /{movieId}}보다 먼저 매칭된다. {@code query}가 비어 있으면 서비스가
     * {@code INVALID_INPUT_VALUE}를 던진다({@code TheaterQueryService}와 같은 검증 위치 원칙).
     */
    @Operation(summary = "영화 검색 (DB + TMDB 병합)")
    @GetMapping("/search")
    public ResponseEntity<MovieSearchResponse> searchMovies(
            @RequestParam String query,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "1") int page) {
        return ResponseEntity.ok(movieSearchService.search(query, year, page));
    }

    @Operation(summary = "영화 상세 조회")
    @GetMapping("/{movieId}")
    public ResponseEntity<MovieDetailResponse> getMovieDetail(@PathVariable Long movieId) {
        return ResponseEntity.ok(movieQueryService.getMovieDetail(movieId));
    }

    @Operation(summary = "영화 전체 출연진 조회 (페이징)")
    @GetMapping("/{movieId}/cast")
    public ResponseEntity<PageResponse<ActorResponse>> getMovieCast(
            @PathVariable Long movieId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(movieQueryService.getMovieCast(movieId, pageable)));
    }
}
