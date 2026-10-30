package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.user.entity.CoverType;

/**
 * 프로필 커버 (profile-spec 3-4). 응답에서 {@code cover}가 {@code null}이면 앱 기본 그라데이션이다.
 * {@code MOVIE} — {@code backdropPath · movieId · title}만 채운다(앱이 TMDB URL을 조립). {@code IMAGE} — {@code imageUrl}만 채운다(서버가 완성한 URL).
 */
public record ProfileCoverResponse(CoverType type, String imageUrl, String backdropPath, Long movieId, String title) {

    public static ProfileCoverResponse movie(Long movieId, String title, String backdropPath) {
        return new ProfileCoverResponse(CoverType.MOVIE, null, backdropPath, movieId, title);
    }

    public static ProfileCoverResponse image(String imageUrl) {
        return new ProfileCoverResponse(CoverType.IMAGE, imageUrl, null, null, null);
    }
}
