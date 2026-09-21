package com.project.cinemory.domain.report.dto;

/** {@code watchType}은 {@code THEATER}/{@code OTT}/{@code ETC}/{@code UNSPECIFIED}(4-3) — enum이 아닌 String이다. */
public record WatchTypeCountResponse(String watchType, long count) {
}
