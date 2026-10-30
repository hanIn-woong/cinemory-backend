package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 관리자 회원 상세(admin-spec 3-3) — 목록 필드 + 프로필·공개 범위·활동 지표. */
public record AdminUserDetailResponse(
        Long userId,
        String email,
        String nickname,
        RoleType role,
        boolean hasPassword,
        List<OAuthProvider> socialProviders,
        LocalDateTime createdAt,
        String profileImage,
        PrivacySetting privacySetting,
        LocalDateTime updatedAt,
        Activity activity
) {

    /**
     * @param watchRecordCount  전체 회차
     * @param watchedMovieCount 본 영화 수 = 대표 기록 수
     * @param lastWatchDate     {@code MAX(watch_date)}, 없으면 null
     */
    public record Activity(
            long watchRecordCount,
            long watchedMovieCount,
            long reviewCount,
            long wishCount,
            long collectionCount,
            long followerCount,
            long followingCount,
            LocalDate lastWatchDate
    ) {
    }

    public static AdminUserDetailResponse of(User user, String profileImageUrl, List<OAuthProvider> socialProviders,
                                           Activity activity) {
        return new AdminUserDetailResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.hasPassword(), socialProviders, user.getCreatedAt(), profileImageUrl,
                user.getPrivacySetting(), user.getUpdatedAt(), activity);
    }
}
