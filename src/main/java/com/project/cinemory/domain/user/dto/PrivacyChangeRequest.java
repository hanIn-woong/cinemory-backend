package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.user.entity.PrivacySetting;
import jakarta.validation.constraints.NotNull;

public record PrivacyChangeRequest(

        @NotNull(message = "공개범위는 필수입니다.")
        PrivacySetting privacySetting
) {
}
