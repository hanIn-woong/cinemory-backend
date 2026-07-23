package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;

public record UserResponse(Long id, String email, String nickname, String profileImage,
                            PrivacySetting privacySetting) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImage(),
                user.getPrivacySetting()
        );
    }
}
