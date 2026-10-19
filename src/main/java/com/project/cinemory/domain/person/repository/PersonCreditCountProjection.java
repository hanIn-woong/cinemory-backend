package com.project.cinemory.domain.person.repository;

/** 인물별 출연 또는 연출 건수 — 관리자 인물 목록의 {@code creditCount}를 페이지의 ID로 묶어 1쿼리씩 센다. */
public interface PersonCreditCountProjection {
    Long getPersonId();

    Long getCount();
}
