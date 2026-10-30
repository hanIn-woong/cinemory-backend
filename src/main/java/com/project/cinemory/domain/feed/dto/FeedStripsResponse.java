package com.project.cinemory.domain.feed.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code me}는 첫 페이지에만 싣는다(활동이 없어도 {@code frames: []}로 항상).
 * {@code newFollowerCount}는 첫 페이지에서 {@code followersSeenAfter}가 있을 때만 싣는다(social-feed-spec 10-3) — 아니면 {@code null}.
 */
public record FeedStripsResponse(LocalDateTime asOf, FeedStripResponse me, List<FeedStripResponse> content,
                                 String nextCursor, boolean hasNext, Long newFollowerCount) {
}
