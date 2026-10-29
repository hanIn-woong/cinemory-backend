package com.project.cinemory.domain.feed.dto;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code rating}은 서버 단위(1~10) 그대로 — 표시 변환은 앱. ⚠️ {@code private_review}는 필드 자체를 두지 않는다(SF-10).
 * {@code recordCount}는 스트립 프레임에서만 채운다 — 합쳐진 시청 기록 수(1 이상, 프레임 창 안의 기록만). 개인 티켓 피드는 {@code null}.
 */
public record WatchDetail(LocalDate watchDate, WatchType watchType, String placeDetail,
                          String ottPlatformName, BigDecimal rating, Integer recordCount) {

    public WatchDetail withRecordCount(Integer count) {
        return new WatchDetail(watchDate, watchType, placeDetail, ottPlatformName, rating, count);
    }

    public static WatchDetail from(WatchRecord record) {
        return new WatchDetail(
                record.getWatchDate(),
                record.getWatchType(),
                record.getPlaceDetail(),
                record.getOttPlatform() != null ? record.getOttPlatform().getName() : null,
                record.getRating(),
                null);
    }
}
