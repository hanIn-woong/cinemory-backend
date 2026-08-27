package com.project.cinemory.domain.movie.controller;

import com.project.cinemory.domain.movie.dto.MovieResyncResponse;
import com.project.cinemory.domain.movie.dto.MovieSeedResponse;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영화 시드 관리자 엔드포인트 (tmdb-sync-spec 6-5).
 *
 * <p>인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다(5-6-B 확정) —
 * Controller에 {@code @PreAuthorize}를 중복으로 달지 않는다. {@code domain/boxoffice/controller}의
 * {@code AdminController}와 같은 패턴(5-6-C ③ — 패키지는 경로가 아니라 호출하는 Service가 소유).
 *
 * <p>박스오피스 역방향과 discover를 하나의 엔드포인트로 합치지 않는다 — 실패 양상과 이어받기
 * 지점이 다르다(역방향=제목 매칭 실패를 개별 건너뜀, discover=페이지 순회 실패로 전체 중단).
 *
 * <p>{@code limit}/{@code pages}는 {@code required = false}로 받아 {@code null}을 그대로
 * Service에 넘긴다 — 기본값 판단은 Controller가 아니라 Service 소관이다(5-6-A와 동일한 규칙).
 */
// 빈 이름은 단순 클래스명으로 정해져 domain.boxoffice.controller.AdminController와
// 충돌한다("Annotation-specified bean name 'adminController' ... conflicts") — 명시 지정으로 해소.
@RestController("movieAdminController")
@RequiredArgsConstructor
public class AdminController {

    private final MovieSeedService movieSeedService;

    @Operation(summary = "박스오피스 역방향 영화 시드")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/movies/seed/box-office")
    public ResponseEntity<MovieSeedResponse> seedFromBoxOffice(@RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(MovieSeedResponse.from(movieSeedService.seedFromBoxOffice(limit)));
    }

    /**
     * discover 보충 시드 — 프로필을 파라미터로 받는다 (tmdb-sync-spec 6-5 "discover 시드
     * 구성 전략"). {@code region=KR&sort_by=popularity.desc} 하드코딩을 철회했으므로
     * 호출부(운영자)가 매번 프로필(한국 영화 / 전역 인지도 / 최근작)에 맞는 값을 넘겨야 한다.
     */
    @Operation(summary = "discover 보충 영화 시드 (프로필 파라미터 pass-through)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/movies/seed/discover")
    public ResponseEntity<MovieSeedResponse> seedFromDiscover(@RequestParam(required = false) Integer pages,
                                                                @RequestParam(required = false) String lang,
                                                                @RequestParam(required = false) Integer minVotes,
                                                                @RequestParam(required = false) String sortBy,
                                                                @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(MovieSeedResponse.from(
                movieSeedService.seedFromDiscover(pages, lang, minVotes, sortBy, year)));
    }

    @Operation(summary = "전체 영화 재동기화 (v13 신규 컬럼 보강 등, existsByTmdbId 필터 우회)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/movies/resync")
    public ResponseEntity<MovieResyncResponse> resync(@RequestParam(required = false) Long fromId,
                                                        @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(MovieResyncResponse.from(movieSeedService.resync(fromId, limit)));
    }
}
