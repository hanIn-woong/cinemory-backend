package com.project.cinemory.domain.comment.dto;

import com.project.cinemory.domain.user.entity.User;

public record CommentAuthorResponse(Long userId, String nickname, String profileImage) {

    public static CommentAuthorResponse from(User user) {
        return new CommentAuthorResponse(user.getId(), user.getNickname(), user.getProfileImage());
    }
}
