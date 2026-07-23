package com.project.cinemory.repository;

import com.project.cinemory.domain.collection.entity.CollectionMovie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionMovieRepository extends JpaRepository<CollectionMovie, Long> {
}
