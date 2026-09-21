package com.project.cinemory.domain.report.dto;

/** {@code weekday}는 MySQL {@code DAYOFWEEK()} 기준 1(일요일)~7(토요일)(4-7). */
public record WeekdayCountResponse(int weekday, long count) {
}
