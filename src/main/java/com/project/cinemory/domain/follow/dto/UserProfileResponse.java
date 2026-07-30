package com.project.cinemory.domain.follow.dto;

import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;

/**
 * 프로필 화면 헤더.
 *
 * <p>비공개 계정이라도 헤더(닉네임/팔로우 수)는 노출한다.
 * 헤더까지 차단하면 팔로우 요청을 보낼 화면 자체가 없어지기 때문이며,
 * 차단 대상은 시청기록/컬렉션/리뷰 등 <b>콘텐츠</b>다.
 */
public record UserProfileResponse(
        Long userId,
        String nickname,
        String profileImage,
        PrivacySetting privacySetting,
        long followerCount,
        long followingCount,
        boolean following,
        boolean me
) {

    public static UserProfileResponse from(User user,
                                           long followerCount,
                                           long followingCount,
                                           boolean following,
                                           boolean me) {
        return new UserProfileResponse(
                user.getId(),
                user.getNickname(),
                user.getProfileImage(),
                user.getPrivacySetting(),
                followerCount,
                followingCount,
                following,
                me
        );
    }
}
