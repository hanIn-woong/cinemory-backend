package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;

import java.time.LocalDate;

/** 영화 상세의 누적 관객(service-layer-spec 4-2-B ②) - "개봉 중"인 영화만 채워진다. {@code targetDate}는 집계 기준일. */
public record BoxOfficeSnapshotResponse(long audienceAcc, LocalDate targetDate) {

    public static BoxOfficeSnapshotResponse from(BoxOfficeRecord record) {
        return new BoxOfficeSnapshotResponse(record.getAudienceAcc(), record.getTargetDate());
    }
}
