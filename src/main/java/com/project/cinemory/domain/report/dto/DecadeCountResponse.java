package com.project.cinemory.domain.report.dto;

/** {@code decade}는 "2020s"~"~1960s" 또는 "UNKNOWN"(4-4). */
public record DecadeCountResponse(String decade, long count) {
}
