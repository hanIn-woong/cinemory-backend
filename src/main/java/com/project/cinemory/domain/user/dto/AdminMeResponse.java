package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.user.entity.User;

/** 관리자 웹이 "이 토큰이 관리자인가"를 서버에 묻는 응답(admin-spec 3-1). 일반 사용자는 이 응답에 도달하지 못한다(403). */
public record AdminMeResponse(Long userId, String email, String nickname) {

    public static AdminMeResponse from(User user) {
        return new AdminMeResponse(user.getId(), user.getEmail(), user.getNickname());
    }
}
