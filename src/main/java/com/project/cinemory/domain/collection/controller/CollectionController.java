package com.project.cinemory.domain.collection.controller;

import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionRequest;
import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionResponse;
import com.project.cinemory.domain.collection.dto.CollectionCreateRequest;
import com.project.cinemory.domain.collection.dto.CollectionMovieListItemResponse;
import com.project.cinemory.domain.collection.dto.CollectionResponse;
import com.project.cinemory.domain.collection.dto.CollectionUpdateRequest;
import com.project.cinemory.domain.collection.service.CollectionService;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * 조회는 {@code /api/users/{userId}/collections}(소유자 스코프) ·
 * {@code /api/collections/{collectionId}/movies}(공개, 5-0-F 화이트리스트 추가분), 나머지 쓰기는
 * {@code /api/collections/**}(주체를 경로에 넣지 않음)로 갈린다 — 클래스 레벨
 * {@code @RequestMapping} 없이 메서드마다 전체 경로를 명시한다.
 */
@RestController
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;

    @Operation(summary = "사용자 컬렉션 목록 조회")
    @GetMapping("/api/users/{userId}/collections")
    public ResponseEntity<PageResponse<CollectionResponse>> getCollections(
            @AuthUser Long viewerId,
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(collectionService.getCollections(viewerId, userId, pageable)));
    }

    @Operation(summary = "컬렉션에 담긴 영화 목록 조회")
    @GetMapping("/api/collections/{collectionId}/movies")
    public ResponseEntity<PageResponse<CollectionMovieListItemResponse>> getCollectionMovies(
            @AuthUser Long viewerId,
            @PathVariable Long collectionId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(collectionService.getCollectionMovies(viewerId, collectionId, pageable)));
    }

    @Operation(summary = "컬렉션 생성")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/collections")
    public ResponseEntity<CollectionResponse> createCollection(
            @AuthUser(required = true) Long userId,
            @Valid @RequestBody CollectionCreateRequest request) {
        CollectionResponse response = collectionService.createCollection(userId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{collectionId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "컬렉션 이름/설명 수정")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/api/collections/{collectionId}")
    public ResponseEntity<CollectionResponse> updateCollection(
            @AuthUser(required = true) Long userId,
            @PathVariable Long collectionId,
            @Valid @RequestBody CollectionUpdateRequest request) {
        return ResponseEntity.ok(collectionService.updateCollection(userId, collectionId, request));
    }

    @Operation(summary = "컬렉션 삭제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/api/collections/{collectionId}")
    public ResponseEntity<Void> deleteCollection(
            @AuthUser(required = true) Long userId,
            @PathVariable Long collectionId) {
        collectionService.deleteCollection(userId, collectionId);
        return ResponseEntity.noContent().build();
    }

    /** idempotent 벌크 추가 — 생성된 단일 리소스를 가리킬 Location이 없으므로 200이다(4-5 확정). */
    @Operation(summary = "컬렉션에 영화 추가")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/collections/{collectionId}/movies")
    public ResponseEntity<AddMoviesToCollectionResponse> addMoviesToCollection(
            @AuthUser(required = true) Long userId,
            @PathVariable Long collectionId,
            @Valid @RequestBody AddMoviesToCollectionRequest request) {
        return ResponseEntity.ok(collectionService.addMoviesToCollection(userId, collectionId, request));
    }

    @Operation(summary = "컬렉션에서 영화 제거")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/api/collections/{collectionId}/movies/{movieId}")
    public ResponseEntity<Void> removeMovieFromCollection(
            @AuthUser(required = true) Long userId,
            @PathVariable Long collectionId,
            @PathVariable Long movieId) {
        collectionService.removeMovieFromCollection(userId, collectionId, movieId);
        return ResponseEntity.noContent().build();
    }
}
