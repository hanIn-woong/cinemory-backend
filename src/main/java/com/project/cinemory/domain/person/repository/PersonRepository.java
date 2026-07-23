package com.project.cinemory.domain.person.repository;

import com.project.cinemory.domain.person.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {
}
