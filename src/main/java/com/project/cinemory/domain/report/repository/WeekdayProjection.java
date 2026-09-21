package com.project.cinemory.domain.report.repository;

/** 요일별 분포(4-7). {@code weekday}는 MySQL {@code DAYOFWEEK()} 기준 1(일)~7(토). */
public interface WeekdayProjection {
    Integer getWeekday();
    Long getCount();
}
