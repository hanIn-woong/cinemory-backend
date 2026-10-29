package com.project.cinemory.domain.feed.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ActivityPageResponse(LocalDateTime asOf, List<ActivityResponse> content, String nextCursor, boolean hasNext) {
}
