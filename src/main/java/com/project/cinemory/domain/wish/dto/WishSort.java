package com.project.cinemory.domain.wish.dto;

import org.springframework.data.domain.Sort;

/**
 * 위시리스트 정렬 — {@code RecordSort}와 같은 화이트리스트 패턴. 찜에는 관람일·별점이 없어
 * 그 둘을 뺀 부분집합이다. NULL 위치·보조키 규칙은 {@code RecordSort}와 같다.
 */
public enum WishSort {

    RECENT(Sort.by(Sort.Order.desc("id"))),
    OLDEST(Sort.by(Sort.Order.asc("id"))),
    TITLE(Sort.by(Sort.Order.asc("movie.sortTitle"), Sort.Order.desc("id"))),
    TITLE_DESC(Sort.by(Sort.Order.desc("movie.sortTitle"), Sort.Order.desc("id"))),
    RELEASE_DESC(Sort.by(Sort.Order.desc("movie.releaseDate").nullsLast(), Sort.Order.desc("id"))),
    RELEASE_ASC(Sort.by(Sort.Order.asc("movie.releaseDate").nullsLast(), Sort.Order.desc("id"))),
    // runtime은 TMDB가 0을 줄 수 있어 저장 전에 null로 정규화된다 — 정보 없음이 맨 위에 오지 않게 NULL은 뒤로 (RecordSort와 같다).
    RUNTIME_ASC(Sort.by(Sort.Order.asc("movie.runtime").nullsLast(), Sort.Order.desc("id"))),
    RUNTIME_DESC(Sort.by(Sort.Order.desc("movie.runtime").nullsLast(), Sort.Order.desc("id")));

    private final Sort sort;

    WishSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
