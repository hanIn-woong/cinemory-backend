package com.project.cinemory.domain.user.dto;

import java.time.Instant;

/** {@code uploadUrl}로 {@code Content-Type: image/jpeg} PUT 한 뒤, {@code key}를 {@code PUT /api/users/me/profile-image}에 넘긴다. */
public record ProfileImageUploadUrlResponse(String uploadUrl, String key, Instant expiresAt) {
}
