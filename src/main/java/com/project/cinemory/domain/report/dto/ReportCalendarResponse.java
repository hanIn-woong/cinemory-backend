package com.project.cinemory.domain.report.dto;

import com.project.cinemory.domain.report.repository.CalendarRecordProjection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 하루에 여러 편이 가능하므로 {@code records}는 배열이다 — 와이어프레임의 날짜당 한 편 가정과 다르다(6절). */
public record ReportCalendarResponse(int year, int month, List<CalendarDayResponse> days) {

    public record CalendarDayResponse(LocalDate date, List<CalendarRecordItemResponse> records) {
    }

    public record CalendarRecordItemResponse(Long recordId, Long movieId, String title, String posterPath,
                                              BigDecimal rating) {

        public static CalendarRecordItemResponse from(CalendarRecordProjection projection) {
            return new CalendarRecordItemResponse(projection.getRecordId(), projection.getMovieId(),
                    projection.getTitle(), projection.getPosterPath(), projection.getRating());
        }
    }
}
