package com.project.cinemory.domain.notification.controller;

import com.project.cinemory.domain.notification.dto.NotificationPageResponse;
import com.project.cinemory.domain.notification.dto.NotificationReadRequest;
import com.project.cinemory.domain.notification.dto.UnreadCountResponse;
import com.project.cinemory.domain.notification.service.NotificationService;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 목록 (키셋 커서)")
    @GetMapping
    public ResponseEntity<NotificationPageResponse> list(@AuthUser(required = true) Long userId,
                                                         @RequestParam(required = false) Long cursor,
                                                         @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(notificationService.getNotifications(userId, cursor, size));
    }

    @Operation(summary = "안 읽은 알림 수")
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthUser(required = true) Long userId) {
        return ResponseEntity.ok(notificationService.getUnreadCount(userId));
    }

    @Operation(summary = "알림 일괄 읽음 (upToId 이하)")
    @PostMapping("/read")
    public ResponseEntity<Void> markRead(@AuthUser(required = true) Long userId,
                                         @Valid @RequestBody NotificationReadRequest request) {
        notificationService.markRead(userId, request.upToId());
        return ResponseEntity.noContent().build();
    }
}
