package com.project.cinemory.domain.movie.dto;

import org.springframework.data.domain.Sort;

/** 관리자 영화 목록 정렬 화이트리스트(admin-spec 3-4). 공개 개봉일이 없는 영화는 뒤로 보낸다. */
public enum AdminMovieSort {

    ID_DESC(Sort.by(Sort.Order.desc("id"))),
    RELEASE_DATE_DESC(Sort.by(Sort.Order.desc("releaseDate").nullsLast(), Sort.Order.desc("id"))),
    // "오래 동기화 안 된 것" — 기록 없음(NULL, V26 도입 전)이 맨 앞
    SYNCED_AT_ASC(Sort.by(Sort.Order.asc("syncedAt").nullsFirst(), Sort.Order.asc("id")));

    private final Sort sort;

    AdminMovieSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
