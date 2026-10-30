package com.project.cinemory.domain.review.dto;

import com.project.cinemory.domain.user.entity.User;

public record ReviewAuthorResponse(Long userId, String nickname, String profileImage) {

    /** @param profileImageUrl {@code ProfileImageUrlResolver}로 해석한 URL — 저장된 키를 그대로 넘기지 않는다 */
    public static ReviewAuthorResponse from(User user, String profileImageUrl) {
        return new ReviewAuthorResponse(user.getId(), user.getNickname(), profileImageUrl);
    }
}
