package com.project.cinemory.domain.feed.dto;

import java.time.LocalDateTime;
import java.util.List;

/** {@code lastActivityAt}은 활동이 없는 {@code me}에서만 {@code null}이다. */
public record FeedStripResponse(FeedUserResponse user, LocalDateTime lastActivityAt, int newCount,
                                List<ActivityResponse> frames) {
}
