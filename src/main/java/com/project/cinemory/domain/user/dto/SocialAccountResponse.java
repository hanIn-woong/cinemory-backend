package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.user.entity.UserSocialAccount;

import java.time.LocalDateTime;

public record SocialAccountResponse(OAuthProvider provider, LocalDateTime linkedAt) {

    public static SocialAccountResponse from(UserSocialAccount account) {
        return new SocialAccountResponse(account.getProvider(), account.getCreatedAt());
    }
}
