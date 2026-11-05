package com.project.cinemory.domain.theater.dto;

/** 극장 이름 덮어쓰기(admin-spec 19-3). {@code null}·빈 문자열은 해제 - 규칙 이름으로 돌아간다. */
public record AdminTheaterNameRequest(String name) {
}
