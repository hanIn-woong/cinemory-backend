package com.project.cinemory.domain.country.controller;

import com.project.cinemory.domain.country.dto.CountrySeedResponse;
import com.project.cinemory.domain.country.service.CountrySeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 국가 참조 테이블 시드 진입점 (tmdb-sync-spec 6-1 서비스 / 6-5 엔드포인트).
 *
 * <p>{@code domain/genre/controller.AdminController}와 대칭이다 — 합치지 않는 이유는
 * 그쪽 Javadoc 참고. <b>영화 시드보다 먼저 실행해야 한다.</b>
 */
// 빈 이름 충돌 방지 — domain.movie.controller.AdminController의 주석 참고.
@RestController("countryAdminController")
@RequiredArgsConstructor
public class AdminController {

    private final CountrySeedService countrySeedService;

    @Operation(summary = "TMDB 국가 참조 테이블 시드")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/admin/countries/seed")
    public ResponseEntity<CountrySeedResponse> seedCountries() {
        return ResponseEntity.ok(new CountrySeedResponse(countrySeedService.seedAll()));
    }
}
