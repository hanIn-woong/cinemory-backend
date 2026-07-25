package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.time.LocalDate;

public record WatchRecordResponse(
        Long id,
        Long movieId,
        LocalDate watchDate,
        boolean representative,
        WatchType watchType,
        String placeDetail,
        OttPlatformResponse ottPlatform,
        Double rating,
        String note
) {

    public static WatchRecordResponse from(WatchRecord watchRecord) {
        return new WatchRecordResponse(
                watchRecord.getId(),
                watchRecord.getMovie().getId(),
                watchRecord.getWatchDate(),
                watchRecord.isRepresentative(),
                watchRecord.getWatchType(),
                watchRecord.getPlaceDetail(),
                watchRecord.getOttPlatform() != null ? OttPlatformResponse.from(watchRecord.getOttPlatform()) : null,
                watchRecord.getRating(),
                watchRecord.getNote()
        );
    }
}
