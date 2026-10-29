package com.project.cinemory.domain.feed.repository;

import java.time.LocalDate;

/** {@code COLLECTION_MOVIES_ADDED} 그룹 조립용 — 컬렉션에 추가된 영화 한 건(포스터 없으면 {@code null}). */
public record AddedMovieRow(Long collectionId, LocalDate addedDate, String posterPath) {
}
