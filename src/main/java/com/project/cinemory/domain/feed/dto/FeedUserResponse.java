package com.project.cinemory.domain.feed.dto;

import com.project.cinemory.domain.user.entity.User;

public record FeedUserResponse(Long userId, String nickname, String profileImage) {

    /** @param profileImageUrl {@code ProfileImageUrlResolver}로 해석한 URL — 저장된 키를 그대로 넘기지 않는다 */
    public static FeedUserResponse from(User user, String profileImageUrl) {
        return new FeedUserResponse(user.getId(), user.getNickname(), profileImageUrl);
    }
}
