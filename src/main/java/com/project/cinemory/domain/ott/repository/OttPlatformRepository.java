package com.project.cinemory.domain.ott.repository;

import com.project.cinemory.domain.ott.entity.OttPlatform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OttPlatformRepository extends JpaRepository<OttPlatform, Long> {

    List<OttPlatform> findByActiveTrueOrderByIdAsc();
}
