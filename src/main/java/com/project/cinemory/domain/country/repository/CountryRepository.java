package com.project.cinemory.domain.country.repository;

import com.project.cinemory.domain.country.entity.Country;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<Country, Long> {
}
