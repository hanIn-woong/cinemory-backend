package com.project.cinemory.domain.follow.dto;

import com.project.cinemory.domain.user.dto.FavoriteMovieResponse;
import com.project.cinemory.domain.user.dto.ProfileCoverResponse;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;

import java.util.List;

/**
 * 프로필 화면 헤더 (profile-spec PR-5 · 3-4).
 *
 * <p>헤더를 통째로 막지 않는다 — 막으면 팔로우 요청을 보낼 화면 자체가 없어진다. 대신 <b>PRIVATE이고 본인이 아니면</b>
 * {@code restricted = true}로 두고 {@code bio · favoriteMovies · cover · followerCount · followingCount}를 {@code null}로 내린다
 * (앱에서만 숨기면 API 직접 조회로 보인다). 닉네임·사진·공개 범위·{@code following}·{@code me}는 그대로 — 팔로우 목록 등에 이미 나오는 식별 정보다.
 * 수 필드가 nullable({@code Long})인 이유다. FRIENDS 본문(기록 등)의 맞팔로우 규칙은 이 헤더 규칙과 별개다.
 */
public record UserProfileResponse(
        Long userId,
        String nickname,
        String profileImage,
        PrivacySetting privacySetting,
        Long followerCount,
        Long followingCount,
        boolean following,
        boolean me,
        String bio,
        List<FavoriteMovieResponse> favoriteMovies,
        ProfileCoverResponse cover,
        boolean restricted
) {

    public static UserProfileResponse from(User user,
                                           String profileImageUrl,
                                           long followerCount,
                                           long followingCount,
                                           boolean following,
                                           boolean me,
                                           List<FavoriteMovieResponse> favoriteMovies,
                                           ProfileCoverResponse cover) {
        return new UserProfileResponse(
                user.getId(),
                user.getNickname(),
                profileImageUrl,
                user.getPrivacySetting(),
                followerCount,
                followingCount,
                following,
                me,
                user.getBio(),
                favoriteMovies,
                cover,
                false
        );
    }

    /** PRIVATE이고 본인이 아닐 때 — 식별 정보와 관계(팔로우 여부)만 싣는다. */
    public static UserProfileResponse restricted(User user, String profileImageUrl, boolean following) {
        return new UserProfileResponse(
                user.getId(),
                user.getNickname(),
                profileImageUrl,
                user.getPrivacySetting(),
                null,
                null,
                following,
                false,
                null,
                null,
                null,
                true
        );
    }
}
