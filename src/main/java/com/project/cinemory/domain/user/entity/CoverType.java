package com.project.cinemory.domain.user.entity;

/** 프로필 커버의 종류 (profile-spec PR-4). 응답 전용 구분값이라 컬럼으로 저장하지 않는다 — {@code cover_movie_id}/{@code cover_image} 중 채워진 쪽이 곧 종류다. */
public enum CoverType {
    MOVIE,
    IMAGE
}
