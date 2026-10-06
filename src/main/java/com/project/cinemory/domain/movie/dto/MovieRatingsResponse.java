package com.project.cinemory.domain.movie.dto;

/** "대체가 아니라 병기" — 두 출처를 한 객체로 묶어 타입에서 드러낸다 (service-layer-spec 4-2-A). */
public record MovieRatingsResponse(RatingSummary tmdb, RatingSummary cinemory) {
}
