package com.project.cinemory.domain.feed.dto;

import java.time.LocalDate;
import java.util.List;

/** {@code COLLECTION_MOVIES_ADDED} — (컬렉션, 날짜) 그룹. {@code posters}는 최대 4장. */
public record AddedMovies(LocalDate groupDate, long count, List<String> posters) {
}
