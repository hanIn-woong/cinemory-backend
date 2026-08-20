package com.project.cinemory.domain.person.repository;

import com.project.cinemory.domain.person.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
