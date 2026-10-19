package com.project.cinemory.domain.person.controller;

import com.project.cinemory.domain.person.dto.AdminPersonNameKoRequest;
import com.project.cinemory.domain.person.dto.AdminPersonSummaryResponse;
import com.project.cinemory.domain.person.service.AdminPersonCommandService;
import com.project.cinemory.domain.person.service.AdminPersonQueryService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuditActor;
import com.project.cinemory.global.security.resolver.CurrentAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 인물 한글명 관리(admin-spec 12-3). 인가는 {@code SecurityConfig}의 {@code hasRole('ADMIN')}이 전담한다.
 * 한글명 변경의 감사 로그는 서비스가 같은 트랜잭션에서 남긴다(Controller가 따로 호출하지 않는다).
 */
@RestController
@RequiredArgsConstructor
public class AdminPersonController {

    private final AdminPersonQueryService adminPersonQueryService;
    private final AdminPersonCommandService adminPersonCommandService;

    @Operation(summary = "관리자 인물 목록 — 이름/한글명 검색, 한글 이름 없는 인물만 (id 오름차순 고정, page 0-based)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/admin/persons")
    public ResponseEntity<PageResponse<AdminPersonSummaryResponse>> getPersons(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean missingKorean,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminPersonQueryService.getPersons(
                q, missingKorean, pageable.getPageNumber(), pageable.getPageSize()));
    }

    @Operation(summary = "인물 한글명 변경 — null·빈 문자열은 해제, 같은 값이면 기록 없이 200 (감사 로그 PERSON_NAME_KO_UPDATE)")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/api/admin/persons/{personId}/name-ko")
    public ResponseEntity<AdminPersonSummaryResponse> changeNameKo(@PathVariable Long personId,
                                                                    @Valid @RequestBody AdminPersonNameKoRequest request,
                                                                    @CurrentAdmin AuditActor actor) {
        return ResponseEntity.ok(adminPersonCommandService.changeNameKo(personId, request.nameKo(), actor));
    }
}
