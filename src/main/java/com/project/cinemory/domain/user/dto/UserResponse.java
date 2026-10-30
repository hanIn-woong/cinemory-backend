package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;

import java.time.LocalDateTime;

/**
 * {@code createdAt} — 가입 시각(KST). 리포트 "CineMory와 함께한 N일"의 기준(2026-10-12, M3a-report-spec 4-8).
 * 본인 조회({@code /api/users/me} 계열) 전용 DTO라 다른 사용자에게 노출되지 않는다.
 * {@code profileImage} — {@code ProfileImageUrlResolver}로 해석한 URL이다(account-integrity-spec B-5-1).
 */
public record UserResponse(Long id, String email, String nickname, String profileImage,
                            PrivacySetting privacySetting, LocalDateTime createdAt) {

    public static UserResponse from(User user, String profileImageUrl) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                profileImageUrl,
                user.getPrivacySetting(),
                user.getCreatedAt()
        );
    }
}
