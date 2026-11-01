package com.project.cinemory.domain.feed.dto;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code rating}은 서버 단위(1~10) 그대로 — 표시 변환은 앱. ⚠️ {@code private_review}는 필드 자체를 두지 않는다(SF-10).
 * {@code recordCount}는 스트립 프레임에서만 채운다 — 합쳐진 시청 기록 수(1 이상, 프레임 창 안의 기록만). 개인 티켓 피드는 {@code null}.
 * {@code theaterName}은 목록에서 고른 극장의 이름, 직접 입력이면 null(4-3-C).
 * {@code ottPlatformCustom}은 OTT 직접 입력 플랫폼('기타')이면 true — 피드는 플랫폼을 이름 문자열로만 내려 앱이 판별할 수 없어서 따로 둔다(4-3-B).
 */
public record WatchDetail(LocalDate watchDate, WatchType watchType, String placeDetail,
                          String ottPlatformName, boolean ottPlatformCustom, String theaterName,
                          BigDecimal rating, Integer recordCount) {

    public WatchDetail withRecordCount(Integer count) {
        return new WatchDetail(watchDate, watchType, placeDetail, ottPlatformName, ottPlatformCustom, theaterName, rating, count);
    }

    public static WatchDetail from(WatchRecord record) {
        return new WatchDetail(
                record.getWatchDate(),
                record.getWatchType(),
                record.getPlaceDetail(),
                record.getOttPlatform() != null ? record.getOttPlatform().getName() : null,
                record.getOttPlatform() != null && record.getOttPlatform().isCustom(),
                record.getTheater() != null ? record.getTheater().getName() : null,
                record.getRating(),
                null);
    }
}
