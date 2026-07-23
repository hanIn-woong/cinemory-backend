package com.project.cinemory.domain.genre.repository;

import com.project.cinemory.domain.genre.entity.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Long> {
}
