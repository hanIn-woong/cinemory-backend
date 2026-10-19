package com.project.cinemory.domain.admin.entity;

/** 감사 로그의 대상 종류(admin-spec 11-2). 연관관계 매핑 없이 {@code target_id}와 함께 순수 컬럼으로만 쓴다(BD-5). */
public enum AdminTargetType {
    USER,
    MOVIE,
    PERSON
}
