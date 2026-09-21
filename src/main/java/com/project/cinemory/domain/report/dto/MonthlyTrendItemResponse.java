package com.project.cinemory.domain.report.dto;

public record MonthlyTrendItemResponse(int year, int month, long watchCount, long movieCount, long watchedMinutes) {
}
