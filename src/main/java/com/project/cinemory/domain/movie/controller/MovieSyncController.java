package com.project.cinemory.domain.movie.controller;

import com.project.cinemory.domain.movie.dto.MovieSyncRequest;
import com.project.cinemory.domain.movie.dto.MovieSyncResponse;
import com.project.cinemory.domain.movie.service.MovieSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 온디맨드 영화 동기화 (tmdb-sync-spec 6-5). 검색 결과에서 아직 우리 DB에 없는 영화를
 * 사용자가 선택했을 때 호출된다.
 *
 * <p>{@code MovieController}(공개 조회 전용)와 분리한 이유 — 이 엔드포인트는 쓰기이고
 * <b>인증이 필요하다.</b> 미인증 공개 경로로 두면 임의의 {@code tmdbId}로 우리 DB를 채우는
 * 통로가 된다(잔여 #9). {@code SecurityConfig}의 화이트리스트({@code PUBLIC_POST_ENDPOINTS})에
 * 올리지 않는 것만으로 기본값 {@code authenticated()}가 적용된다 — 별도 설정 불필요.
 *
 * <p>필터하지 않는다 — 사용자가 명시적으로 요청한 것이므로 항상 최신화가 맞다
 * ({@code MovieSeedService}의 {@code existsByTmdbId} 사전 필터와 계약이 다르다, 6-4).
 */
@RestController
@RequiredArgsConstructor
public class MovieSyncController {

    private final MovieSyncService movieSyncService;

    @Operation(summary = "TMDB 영화 온디맨드 동기화")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/movies/sync")
    public ResponseEntity<MovieSyncResponse> syncMovie(@Valid @RequestBody MovieSyncRequest request) {
        return ResponseEntity.ok(MovieSyncResponse.from(movieSyncService.syncFromTmdb(request.tmdbId())));
    }
}
