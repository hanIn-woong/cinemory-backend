package com.project.cinemory.domain.follow.dto;

import com.project.cinemory.domain.user.entity.User;

/**
 * 팔로워/팔로잉 목록 항목.
 *
 * <p>{@code following}은 엔티티가 알 수 없는 "조회자 기준" 계산값이므로
 * {@code from(Entity)} 단일 인자 규칙의 최소 예외로 추가 파라미터를 받는다. {@code profileImageUrl}은
 * {@code ProfileImageUrlResolver}로 해석한 값이다(저장된 키를 그대로 넘기지 않는다).
 */
public record FollowUserResponse(
        Long userId,
        String nickname,
        String profileImage,
        boolean following
) {

    public static FollowUserResponse from(User user, String profileImageUrl, boolean following) {
        return new FollowUserResponse(
                user.getId(),
                user.getNickname(),
                profileImageUrl,
                following
        );
    }
}
