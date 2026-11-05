package com.project.cinemory.domain.boxoffice.repository;

import java.time.LocalDate;

/** 미연결 박스오피스 행을 KOFIC 코드로 묶은 집계(admin-spec 17-1) — JPQL 생성자 표현식용. */
public record UnlinkedBoxOfficeGroup(
        String koficMovieCd, String title, LocalDate openDate,
        LocalDate firstDate, LocalDate lastDate, Integer bestRank, Long recordCount) {
}
