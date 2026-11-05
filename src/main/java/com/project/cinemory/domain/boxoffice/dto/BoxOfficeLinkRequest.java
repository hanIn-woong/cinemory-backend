package com.project.cinemory.domain.boxoffice.dto;

import jakarta.validation.constraints.NotNull;

public record BoxOfficeLinkRequest(@NotNull(message = "movieId는 필수입니다.") Long movieId) {
}
