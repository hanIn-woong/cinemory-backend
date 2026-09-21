package com.project.cinemory.domain.report.dto;

/** 별점 분포 항목. {@code rating}은 1~10 정수 버킷 — 10개 고정, {@code count: 0} 포함(RA-5). */
public record RatingBucketResponse(int rating, long count) {
}
