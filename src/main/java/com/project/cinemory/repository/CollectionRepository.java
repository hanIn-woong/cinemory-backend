package com.project.cinemory.repository;

import com.project.cinemory.domain.collection.entity.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRepository extends JpaRepository<Collection, Long> {
}
