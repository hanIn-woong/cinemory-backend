package com.project.cinemory.domain.report.repository;

/** 별점 분포 원본 행. 존재하는 버킷만 돌려주므로 1~10 고정 채우기는 Service 책임(RA-5). */
public interface RatingBucketProjection {
    Integer getRating();
    Long getCount();
}
