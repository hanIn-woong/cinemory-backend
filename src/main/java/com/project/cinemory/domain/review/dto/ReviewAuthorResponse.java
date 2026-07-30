package com.project.cinemory.domain.review.dto;

import com.project.cinemory.domain.user.entity.User;

public record ReviewAuthorResponse(Long userId, String nickname, String profileImage) {

    public static ReviewAuthorResponse from(User user) {
        return new ReviewAuthorResponse(user.getId(), user.getNickname(), user.getProfileImage());
    }
}
