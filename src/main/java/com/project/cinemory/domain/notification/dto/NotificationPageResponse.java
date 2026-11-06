package com.project.cinemory.domain.notification.dto;

import java.util.List;

public record NotificationPageResponse(List<NotificationResponse> content, Long nextCursor, boolean hasNext) {
}
