package com.project.cinemory.domain.movie.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminMoviePosterPinRequest(@NotBlank(message = "filePath는 필수입니다.") String filePath) {
}
