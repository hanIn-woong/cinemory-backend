package com.project.cinemory.domain.comment.controller;

import com.project.cinemory.domain.comment.dto.CommentCreateRequest;
import com.project.cinemory.domain.comment.dto.CommentResponse;
import com.project.cinemory.domain.comment.dto.CommentUpdateRequest;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.comment.service.CommentService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "댓글 목록 조회")
    @GetMapping
    public ResponseEntity<PageResponse<CommentResponse>> getComments(
            @AuthUser Long viewerId,
            @RequestParam TargetType targetType,
            @RequestParam Long targetId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(commentService.getComments(viewerId, targetType, targetId, pageable)));
    }

    @Operation(summary = "댓글 작성")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<CommentResponse> createComment(
            @AuthUser(required = true) Long authorId,
            @Valid @RequestBody CommentCreateRequest request) {
        CommentResponse response = commentService.createComment(authorId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{commentId}")
                .buildAndExpand(response.commentId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /** 변경된 content는 클라이언트가 이미 알고 있으므로 바디를 돌려줄 필요가 없다(5-5 확정). */
    @Operation(summary = "댓글 수정")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> editComment(
            @AuthUser(required = true) Long viewerId,
            @PathVariable Long commentId,
            @Valid @RequestBody CommentUpdateRequest request) {
        commentService.editComment(viewerId, commentId, request.content());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "댓글 삭제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @AuthUser(required = true) Long viewerId,
            @PathVariable Long commentId) {
        commentService.deleteComment(viewerId, commentId);
        return ResponseEntity.noContent().build();
    }
}
