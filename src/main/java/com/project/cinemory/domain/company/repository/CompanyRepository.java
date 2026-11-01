package com.project.cinemory.domain.company.repository;

import com.project.cinemory.domain.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    // 재동기화 upsert - 영화 한 편의 제작사를 1쿼리로 읽는다(건별 조회 금지, 6-4 ③과 같은 이유)
    List<Company> findByTmdbCompanyIdIn(Collection<Long> tmdbCompanyIds);
}
