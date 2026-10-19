package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 회원 목록 행(admin-spec 3-2). 앱용 DTO와 분리했다 — 서로의 계약 변경이 번지지 않게 한다. */
public record AdminUserSummaryResponse(
        Long userId,
        String email,
        String nickname,
        RoleType role,
        boolean hasPassword,
        List<OAuthProvider> socialProviders,
        LocalDateTime createdAt
) {

    public static AdminUserSummaryResponse of(User user, List<OAuthProvider> socialProviders) {
        return new AdminUserSummaryResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.hasPassword(), socialProviders, user.getCreatedAt());
    }
}
