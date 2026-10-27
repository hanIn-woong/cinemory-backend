package com.project.cinemory.domain.admin.entity;

/**
 * 감사 로그에 남기는 관리자 동작(admin-spec 11-2). 성공한 동작만 남긴다 — 실패는 애플리케이션 로그로 충분하다.
 * 스케줄러가 실행한 박스오피스 동기화는 관리자 행위가 아니므로 남기지 않는다.
 */
public enum AdminAction {
    USER_LIST_VIEW,
    USER_DETAIL_VIEW,
    MOVIE_RESYNC,
    MOVIE_RESYNC_BATCH,
    MOVIE_SEED_BOX_OFFICE,
    MOVIE_SEED_DISCOVER,
    GENRE_SEED,
    COUNTRY_SEED,
    BOX_OFFICE_SYNC,
    THEATER_SEED,                   // CineMap C-4 — dryRun은 쓰기가 없어 남기지 않는다
    BOX_OFFICE_REMATCH,
    PERSON_NAME_KO_UPDATE,
    MOVIE_TITLE_KO_UPDATE,
    EMBEDDING_SYNC,                 // M3-b 임베딩 수동 실행(5-14-G) — 스케줄 실행은 남기지 않는다
    RECOMMENDATION_DEBUG_VIEW       // 특정 회원의 취향 정보 열람 = 회원 개인정보 조회(admin-spec BD-2, M3-b A-1-6)
}
