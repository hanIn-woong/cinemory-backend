package com.project.cinemory.domain.movie.controller;

import com.project.cinemory.domain.movie.dto.ActorResponse;
import com.project.cinemory.domain.movie.dto.MovieDetailResponse;
import com.project.cinemory.domain.movie.dto.MovieListItemResponse;
import com.project.cinemory.domain.movie.service.MovieQueryService;
import com.project.cinemory.global.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영화 공개 조회 전용(5-2). {@code viewerId}를 받지 않는다 — 영화는 사용자 소유 데이터가 아니라
 * {@code UserAccessPolicy} 적용 대상이 아니다(4-7의 Theater/BoxOffice와 동일한 성격).
 *
 * <p>{@code searchMovies}는 이번 단계에서 노출하지 않는다 — {@code MovieSearchCondition} 설계가
 * 미확정이라 지금 노출하면 {@code getMovieList}와 동작이 완전히 같은 두 엔드포인트가 생긴다.
 */
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieQueryService movieQueryService;

    @Operation(summary = "영화 목록 조회")
    @GetMapping
    public ResponseEntity<PageResponse<MovieListItemResponse>> getMovieList(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(movieQueryService.getMovieList(pageable)));
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
