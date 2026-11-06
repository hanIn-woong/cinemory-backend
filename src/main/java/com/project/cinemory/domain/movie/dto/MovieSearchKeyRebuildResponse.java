package com.project.cinemory.domain.movie.dto;

/** @param processed 읽은 영화 수 / @param updated 키가 실제로 바뀐 영화 수(이미 맞는 행은 UPDATE가 나가지 않는다) */
public record MovieSearchKeyRebuildResponse(int processed, int updated) {
}
