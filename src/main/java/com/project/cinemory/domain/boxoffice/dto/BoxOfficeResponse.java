package com.project.cinemory.domain.boxoffice.dto;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;

import java.time.LocalDate;
import java.util.List;

public record BoxOfficeResponse(
        LocalDate targetDate,
        RankType rankType,
        List<BoxOfficeItemResponse> items
) {

    public static BoxOfficeResponse from(LocalDate targetDate, RankType rankType, List<BoxOfficeRecord> records) {
        return new BoxOfficeResponse(
                targetDate,
                rankType,
                records.stream().map(BoxOfficeItemResponse::from).toList()
        );
    }
}
