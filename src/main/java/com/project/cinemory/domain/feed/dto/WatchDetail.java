package com.project.cinemory.domain.feed.dto;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** {@code rating}은 서버 단위(1~10) 그대로 — 표시 변환은 앱. ⚠️ {@code private_review}는 필드 자체를 두지 않는다(SF-10). */
public record WatchDetail(LocalDate watchDate, WatchType watchType, String placeDetail,
                          String ottPlatformName, BigDecimal rating) {

    public static WatchDetail from(WatchRecord record) {
        return new WatchDetail(
                record.getWatchDate(),
                record.getWatchType(),
                record.getPlaceDetail(),
                record.getOttPlatform() != null ? record.getOttPlatform().getName() : null,
                record.getRating());
    }
}
