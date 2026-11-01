package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.ott.dto.OttPlatformResponse;
import com.project.cinemory.domain.theater.dto.TheaterSummaryResponse;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WatchRecordResponse(
        Long id,
        Long movieId,
        LocalDate watchDate,
        boolean representative,
        WatchType watchType,
        String placeDetail,
        OttPlatformResponse ottPlatform,
        TheaterSummaryResponse theater,
        BigDecimal rating,
        String privateReview
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
                watchRecord.getTheater() != null ? TheaterSummaryResponse.from(watchRecord.getTheater()) : null,
                watchRecord.getRating(),
                watchRecord.getPrivateReview()
        );
    }
}
