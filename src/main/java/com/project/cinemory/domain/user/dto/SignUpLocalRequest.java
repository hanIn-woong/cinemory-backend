package com.project.cinemory.domain.user.dto;

public record SignUpLocalRequest(String email, String rawPassword, String nickname) {
}
