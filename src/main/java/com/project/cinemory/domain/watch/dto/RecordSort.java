package com.project.cinemory.domain.watch.dto;

import org.springframework.data.domain.Sort;

/**
 * "내 영화" 목록 정렬 — 클라이언트 자유 {@code sort} 대신 서버가 정의한 화이트리스트(5-0-D 보완).
 * 찜({@code WishSort})과 합치지 않는다: 찜에는 {@code watchDate}·{@code rating}이 없어
 * 합치면 {@code RATING_DESC}가 런타임 400이 된다.
 * <p>
 * nullable 컬럼({@code watchDate}·{@code rating}·{@code movie.releaseDate})은 방향과 무관하게
 * NULL을 뒤로 보낸다 — MySQL 기본(ASC에서 NULL 먼저)대로면 "기억 안 나는 기록"이 맨 위에 온다.
 * 값이 겹칠 수 있는 정렬은 {@code id DESC}를 보조키로 붙인다 — 오프셋 페이징에서 페이지 경계가
 * 흔들리지 않게 순서를 전순서로 고정한다.
 */
public enum RecordSort {

    RECENT(Sort.by(Sort.Order.desc("id"))),
    OLDEST(Sort.by(Sort.Order.asc("id"))),
    TITLE(Sort.by(Sort.Order.asc("movie.title"), Sort.Order.desc("id"))),
    RELEASE_DESC(Sort.by(Sort.Order.desc("movie.releaseDate").nullsLast(), Sort.Order.desc("id"))),
    RELEASE_ASC(Sort.by(Sort.Order.asc("movie.releaseDate").nullsLast(), Sort.Order.desc("id"))),
    WATCH_DATE_DESC(Sort.by(Sort.Order.desc("watchDate").nullsLast(), Sort.Order.desc("id"))),
    RATING_DESC(Sort.by(Sort.Order.desc("rating").nullsLast(), Sort.Order.desc("id")));

    private final Sort sort;

    RecordSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
