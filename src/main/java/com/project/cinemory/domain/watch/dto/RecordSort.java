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
    TITLE(Sort.by(Sort.Order.asc("movie.sortTitle"), Sort.Order.desc("id"))),
    TITLE_DESC(Sort.by(Sort.Order.desc("movie.sortTitle"), Sort.Order.desc("id"))),
    RELEASE_DESC(Sort.by(Sort.Order.desc("movie.releaseDate").nullsLast(), Sort.Order.desc("id"))),
    RELEASE_ASC(Sort.by(Sort.Order.asc("movie.releaseDate").nullsLast(), Sort.Order.desc("id"))),
    // ⚠️ 관람일순 둘은 이 Sort를 쓰지 않는다 — 영화별 MAX/MIN(watchDate) 서브쿼리 정렬이 Repository 쿼리에 박혀 있다(isByWatchDate).
    // 여기 값은 대표 행 자신의 날짜 기준 폴백일 뿐이다.
    WATCH_DATE_DESC(Sort.by(Sort.Order.desc("watchDate").nullsLast(), Sort.Order.desc("id"))),
    WATCH_DATE_ASC(Sort.by(Sort.Order.asc("watchDate").nullsLast(), Sort.Order.desc("id"))),
    RATING_DESC(Sort.by(Sort.Order.desc("rating").nullsLast(), Sort.Order.desc("id"))),
    RATING_ASC(Sort.by(Sort.Order.asc("rating").nullsLast(), Sort.Order.desc("id"))),
    // runtime은 TMDB가 0을 줄 수 있어 저장 전에 null로 정규화된다(TmdbMovieDetailResponse.normalizedRuntime) — 정보 없음이 맨 위에 오지 않게 NULL은 뒤로.
    RUNTIME_ASC(Sort.by(Sort.Order.asc("movie.runtime").nullsLast(), Sort.Order.desc("id"))),
    RUNTIME_DESC(Sort.by(Sort.Order.desc("movie.runtime").nullsLast(), Sort.Order.desc("id")));

    private final Sort sort;

    RecordSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }

    /** 영화별 관람일 집계(MAX/MIN)로 정렬하는 옵션 — 대표 행의 컬럼으로는 재관람 날짜를 정렬에 반영할 수 없어 전용 쿼리를 쓴다. */
    public boolean isByWatchDate() {
        return this == WATCH_DATE_DESC || this == WATCH_DATE_ASC;
    }
}
