package com.project.cinemory.domain.report.dto;

/** {@code watchType}은 {@code THEATER}/{@code OTT}/{@code ETC}/{@code TV}/{@code VOD}/{@code DISC}/{@code UNSPECIFIED}(4-3, 4-3-B에서 TV·VOD·DISC 추가) — enum이 아닌 String이다. */
public record WatchTypeCountResponse(String watchType, long count) {
}
