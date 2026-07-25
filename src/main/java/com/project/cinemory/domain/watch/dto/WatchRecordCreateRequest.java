package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.watch.entity.WatchType;

import java.time.LocalDate;

public record WatchRecordCreateRequest(
        Long movieId,
        LocalDate watchDate,
        WatchType watchType,
        String placeDetail,
        Long ottPlatformId,
        Double rating,
        String note
) {
}
