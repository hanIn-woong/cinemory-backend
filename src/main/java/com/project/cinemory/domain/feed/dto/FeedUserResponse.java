package com.project.cinemory.domain.feed.dto;

import com.project.cinemory.domain.user.entity.User;

public record FeedUserResponse(Long userId, String nickname, String profileImage) {

    public static FeedUserResponse from(User user) {
        return new FeedUserResponse(user.getId(), user.getNickname(), user.getProfileImage());
    }
}
