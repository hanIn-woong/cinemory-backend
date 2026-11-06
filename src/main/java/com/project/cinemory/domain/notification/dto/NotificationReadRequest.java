package com.project.cinemory.domain.notification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record NotificationReadRequest(@NotNull @Positive Long upToId) {
}
