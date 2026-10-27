package com.project.cinemory.domain.theater.dto;

/** 예매 링크의 출처(cinemap-spec C-8-1) — 앱 버튼 문구가 이 값으로 갈린다("온라인 예매" / "{체인}에서 예매"). 링크가 없으면 응답에서 {@code null}. */
public enum BookingSource {
    THEATER,
    CHAIN
}
