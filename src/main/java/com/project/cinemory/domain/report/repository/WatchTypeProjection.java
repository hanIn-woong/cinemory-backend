package com.project.cinemory.domain.report.repository;

/** 관람 방식 분포 원본 행. {@code watchType}이 null이면(4-8-C ④) Service가 UNSPECIFIED로 매핑한다. */
public interface WatchTypeProjection {
    String getWatchType();
    Long getCount();
}
