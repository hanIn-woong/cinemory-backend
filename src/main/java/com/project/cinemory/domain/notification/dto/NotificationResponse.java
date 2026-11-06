package com.project.cinemory.domain.notification.dto;

import com.project.cinemory.domain.follow.dto.FollowUserResponse;
import com.project.cinemory.domain.notification.entity.Notification;
import com.project.cinemory.domain.notification.entity.NotificationType;

import java.time.LocalDateTime;

/** {@code read} — 엔티티 게터는 {@code isRead()}지만 record 컴포넌트명으로 JSON 키를 {@code read}로 고정한다. */
public record NotificationResponse(
        Long id,
        NotificationType type,
        FollowUserResponse actor,
        boolean read,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification, FollowUserResponse actor) {
        return new NotificationResponse(notification.getId(), notification.getType(), actor,
                notification.isRead(), notification.getCreatedAt());
    }
}
