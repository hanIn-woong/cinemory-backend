package com.project.cinemory.domain.report.repository;

/** 개봉 연대 비선형 버킷(4-4). {@code decade}는 "2020s"~"~1960s" 또는 "UNKNOWN". */
public interface DecadeProjection {
    String getDecade();
    Long getCount();
}
