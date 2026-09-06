package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.watch.entity.WatchType;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 전체 치환 의미 — 생략한 필드는 null로 지워진다(service-layer-spec.md 4-3). */
public record WatchRecordUpdateRequest(

        LocalDate watchDate,
        WatchType watchType,

        @Size(max = 100, message = "장소 상세는 100자를 넘을 수 없습니다.")
        String placeDetail,

        Long ottPlatformId,
        Double rating,

        @Size(max = 1000, message = "메모는 1000자를 넘을 수 없습니다.")
        String note
) {
}
