package com.project.cinemory.domain.country.repository;

import com.project.cinemory.domain.country.entity.Country;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CountryRepository extends JpaRepository<Country, Long> {

    /**
     * 시드 적재의 멱등 upsert용. TMDB 국가 목록은 250건 안팎이라
     * 건별 조회로는 왕복이 그만큼 늘어난다.
     */
    List<Country> findByCodeIn(Collection<String> codes);
}
