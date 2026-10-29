package com.project.cinemory.domain.feed.dto;

import java.time.LocalDateTime;
import java.util.List;

/** {@code me}는 첫 페이지에만 싣는다(활동이 없어도 {@code frames: []}로 항상). */
public record FeedStripsResponse(LocalDateTime asOf, FeedStripResponse me, List<FeedStripResponse> content,
                                 String nextCursor, boolean hasNext) {
}
