package com.project.cinemory.domain.boxoffice.repository;

import java.time.LocalDate;

/**
 * {@link BoxOfficeRecordRepository#findUnmatchedTitles} 전용 JPQL 생성자 표현식 프로젝션.
 *
 * <p>박스오피스 역방향 시드(tmdb-sync-spec 6-5)의 입력이다. API 응답 DTO가 아니라
 * 쿼리 결과 캐리어라 {@code dto} 패키지가 아니라 repository 옆에 둔다.
 */
public record UnmatchedBoxOfficeTitle(String movieTitleSnapshot, LocalDate openDate) {
}
