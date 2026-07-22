package com.project.cinemory.repository;

import com.project.cinemory.domain.common.entity.WishMovie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishMovieRepository extends JpaRepository<WishMovie, Long> {
}
