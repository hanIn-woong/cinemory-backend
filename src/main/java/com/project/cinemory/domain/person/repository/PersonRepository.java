package com.project.cinemory.domain.person.repository;

import com.project.cinemory.domain.person.entity.Person;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /**
     * {@code MovieSyncPersister}의 Person 통합 upsert(6-4 3단계)용.
     *
     * <p>cast ∪ directors 합집합을 1쿼리로 조회해 신규/기존을 가른다. 건별 조회는
     * cast 200명이면 200회 왕복이 되고, 배우 겸 감독인 경우 별도 조회 시 flush 전
     * 상태를 못 봐 {@code uk_person_tmdb_id} 위반이 난다 — 그래서 통합 조회가 필수다.
     */
    List<Person> findByTmdbPersonIdIn(Collection<Long> tmdbPersonIds);

    /**
     * 관리자 인물 목록(admin-spec 12-3) — {@code name} 또는 {@code name_ko} 부분 일치({@code LikePattern} + {@code ESCAPE '!'}).
     * {@code missingKorean}이 true면 <b>한글 이름이 하나도 없는 인물만</b>: {@code name_ko IS NULL AND name NOT REGEXP '[가-힣]'}.
     * {@code REGEXP}는 JPQL에 없어 네이티브다. 정렬은 {@code id ASC} 고정. 약 23만 행의 {@code LIKE '%q%'} 풀스캔이므로
     * 운영 실측이 1초를 넘으면 접두 일치로 바꾼다(스펙 12-3).
     */
    @Query(value = """
            SELECT * FROM person p
            WHERE (p.name LIKE :pattern ESCAPE '!' OR p.name_ko LIKE :pattern ESCAPE '!')
              AND (:missingKorean = FALSE OR (p.name_ko IS NULL AND p.name NOT REGEXP '[가-힣]'))
            ORDER BY p.id ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM person p
            WHERE (p.name LIKE :pattern ESCAPE '!' OR p.name_ko LIKE :pattern ESCAPE '!')
              AND (:missingKorean = FALSE OR (p.name_ko IS NULL AND p.name NOT REGEXP '[가-힣]'))
            """,
            nativeQuery = true)
    Page<Person> searchForAdmin(@Param("pattern") String pattern, @Param("missingKorean") boolean missingKorean,
                                Pageable pageable);
}
