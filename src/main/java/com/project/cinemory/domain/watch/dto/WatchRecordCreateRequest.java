package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.watch.entity.WatchType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WatchRecordCreateRequest(

        @NotNull(message = "영화는 필수입니다.")
        Long movieId,

        // 엔티티(watchDate)가 nullable이다 — "오래전에 봐서 날짜가 기억나지 않는 기록"을
        // 허용한 설계이므로 DTO에서 더 엄격하게 굴면 그 설계가 무력화된다(2026-08-07 정정).
        LocalDate watchDate,

        WatchType watchType,
        String placeDetail,
        Long ottPlatformId,
        BigDecimal rating,
        String privateReview,

        // 목록에서 고른 극장(4-3-C) — 관람 방식이 THEATER일 때만. 직접 입력이면 null + placeDetail에 극장명
        Long theaterId
) {

    /** 극장 연결이 없는 기록(theaterId = null) — 기존 호출부 호환. */
    public WatchRecordCreateRequest(Long movieId, LocalDate watchDate, WatchType watchType, String placeDetail,
                                    Long ottPlatformId, BigDecimal rating, String privateReview) {
        this(movieId, watchDate, watchType, placeDetail, ottPlatformId, rating, privateReview, null);
    }
}
