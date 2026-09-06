package com.project.cinemory.domain.genre.controller;

import com.project.cinemory.domain.genre.dto.GenreSeedResponse;
import com.project.cinemory.domain.genre.service.GenreSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장르 참조 테이블 시드 진입점 (tmdb-sync-spec 6-1 서비스 / 6-5 엔드포인트).
 *
 * <p>인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다 —
 * {@code domain/boxoffice/controller.AdminController}와 같은 패턴(5-6-C ③).
 *
 * <p>국가 시드({@code domain/country/controller.AdminController})와 하나로 합치지 않는다 —
 * 합치려면 어느 도메인도 소유하지 않는 오케스트레이션 서비스가 필요해지고, 실패 양상도
 * 다르다(장르 20건 대 국가 250건, 국가에만 ISO 코드 길이 필터). 순서를 지키지 않아도
 * {@code MovieSeedService}의 참조 테이블 가드가 잡아준다.
 *
 * <p><b>영화 시드보다 먼저 실행해야 한다.</b> 실행 순서는 참조 테이블 → 영화 시드다.
 */
// 빈 이름 충돌 방지 — domain.movie.controller.AdminController의 주석 참고.
@RestController("genreAdminController")
@RequiredArgsConstructor
public class AdminController {

    private final GenreSeedService genreSeedService;

    @Operation(summary = "TMDB 장르 참조 테이블 시드")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/genres/seed")
    public ResponseEntity<GenreSeedResponse> seedGenres() {
        return ResponseEntity.ok(new GenreSeedResponse(genreSeedService.seedAll()));
    }
}
